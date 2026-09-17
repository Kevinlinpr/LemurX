// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/ui/android/lemurx/lemurx_luakit_native.h"

#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/command_line.h"
#include "base/functional/bind.h"
#include "base/json/json_reader.h"
#include "base/json/json_writer.h"
#include "base/logging.h"
#include "base/functional/callback.h"
#include "base/process/launch.h"
#include "base/process/process.h"
#include "base/task/task_traits.h"
#include "base/task/thread_pool.h"
#include "base/time/time.h"
#include "base/values.h"
#include "chrome/browser/ui/android/lemurx/lemurx_cdp.h"
#include "chrome/browser/ui/android/lemurx/lemurx_engine.h"
#include "third_party/lua/src/lauxlib.h"
#include "third_party/lua/src/lua.h"
#include "third_party/re2/src/re2/re2.h"
#include "third_party/sqlite/sqlite3.h"
#include "url/gurl.h"

namespace {

// ===== 小工具 =====

void SetFn(lua_State* L, const char* name, lua_CFunction fn) {
  lua_pushcfunction(L, fn);
  lua_setfield(L, -2, name);
}

int PushNilErr(lua_State* L, const std::string& err) {
  lua_pushnil(L);
  lua_pushlstring(L, err.data(), err.size());
  return 2;
}

std::vector<std::string> ReadStringList(lua_State* L, int index) {
  std::vector<std::string> out;
  index = lua_absindex(L, index);
  if (lua_type(L, index) != LUA_TTABLE) {
    return out;
  }
  lua_Integer n = luaL_len(L, index);
  for (lua_Integer i = 1; i <= n; ++i) {
    lua_rawgeti(L, index, i);
    size_t len = 0;
    const char* s = lua_tolstring(L, -1, &len);
    out.emplace_back(s ? std::string(s, len) : std::string());
    lua_pop(L, 1);
  }
  return out;
}

const base::TimeTicks& StartTicks() {
  static const base::TimeTicks ticks = base::TimeTicks::Now();
  return ticks;
}

// ===== RE2 正则 =====
// luakit 的 regex 类用 GRegex（PCRE）；这里用 Chromium 自带的 RE2。
// 语法上 RE2 是 PCRE 的子集（没有回溯断言），luakit 自带模块（adblock 等）
// 用到的模式都在子集内。

std::map<int, std::unique_ptr<RE2>>& Regexes() {
  static std::map<int, std::unique_ptr<RE2>> m;
  return m;
}
int g_next_regex_id = 1;

int RegexCompile(lua_State* L) {
  size_t len = 0;
  const char* pattern = luaL_checklstring(L, 1, &len);
  RE2::Options opts;
  opts.set_log_errors(false);
  auto re = std::make_unique<RE2>(std::string(pattern, len), opts);
  if (!re->ok()) {
    return PushNilErr(L, re->error());
  }
  int id = g_next_regex_id++;
  Regexes()[id] = std::move(re);
  lua_pushinteger(L, id);
  return 1;
}

int RegexMatch(lua_State* L) {
  int id = static_cast<int>(luaL_checkinteger(L, 1));
  size_t len = 0;
  const char* s = luaL_checklstring(L, 2, &len);
  auto it = Regexes().find(id);
  if (it == Regexes().end()) {
    return luaL_error(L, "regex: stale handle %d", id);
  }
  lua_pushboolean(L, RE2::PartialMatch(std::string_view(s, len), *it->second));
  return 1;
}

int RegexFree(lua_State* L) {
  int id = static_cast<int>(luaL_checkinteger(L, 1));
  Regexes().erase(id);
  return 0;
}

// ===== sqlite3 多实例 =====
// 语义对齐 luakit clib/sqlite3.c：
//  * exec 支持多条语句，bindings 对每条语句都绑一遍，返回最后一条的结果；
//  * 有列的语句返回 rows 表（哪怕 0 行），没列的返回 nil；
//  * 数值列一律 number，TEXT/BLOB 一律 string，NULL 不落键；
//  * bindings 表：整数键 = 位置参数，字符串键 = 命名参数（":name"/"@name"/"$name"）。

struct SqliteDb {
  sqlite3* db = nullptr;
  std::string filename;
};

std::map<int, SqliteDb>& Dbs() {
  static std::map<int, SqliteDb> m;
  return m;
}
std::map<int, sqlite3_stmt*>& Stmts() {
  static std::map<int, sqlite3_stmt*> m;
  return m;
}
int g_next_db_id = 1;
int g_next_stmt_id = 1;

SqliteDb* CheckDb(lua_State* L, int index) {
  int id = static_cast<int>(luaL_checkinteger(L, index));
  auto it = Dbs().find(id);
  if (it == Dbs().end() || !it->second.db) {
    luaL_error(L, "sqlite3: database closed");
    return nullptr;
  }
  return &it->second;
}

int SqliteOpen(lua_State* L) {
  const char* filename = luaL_checkstring(L, 1);
  sqlite3* db = nullptr;
  int rc = sqlite3_open(filename, &db);
  if (rc != SQLITE_OK) {
    std::string err = db ? sqlite3_errmsg(db) : "sqlite3_open failed";
    if (db) {
      sqlite3_close(db);
    }
    return PushNilErr(L, err);
  }
  int id = g_next_db_id++;
  Dbs()[id] = SqliteDb{db, filename};
  lua_pushinteger(L, id);
  return 1;
}

int SqliteClose(lua_State* L) {
  int id = static_cast<int>(luaL_checkinteger(L, 1));
  auto it = Dbs().find(id);
  if (it == Dbs().end()) {
    return 0;
  }
  // 先收掉挂在这个库上的预编译语句
  for (auto s = Stmts().begin(); s != Stmts().end();) {
    if (sqlite3_db_handle(s->second) == it->second.db) {
      sqlite3_finalize(s->second);
      s = Stmts().erase(s);
    } else {
      ++s;
    }
  }
  sqlite3_close(it->second.db);
  Dbs().erase(it);
  return 0;
}

int SqliteChanges(lua_State* L) {
  SqliteDb* d = CheckDb(L, 1);
  lua_pushinteger(L, sqlite3_changes(d->db));
  return 1;
}

// 把 bindings 表（栈位 index）绑到 stmt 上。
bool BindAll(lua_State* L, sqlite3_stmt* stmt, int index, std::string* err) {
  if (lua_isnoneornil(L, index)) {
    return true;
  }
  index = lua_absindex(L, index);
  lua_pushnil(L);
  while (lua_next(L, index) != 0) {
    int bidx = 0;
    if (lua_type(L, -2) == LUA_TNUMBER) {
      bidx = static_cast<int>(lua_tointeger(L, -2));
    } else if (lua_type(L, -2) == LUA_TSTRING) {
      bidx = sqlite3_bind_parameter_index(stmt, lua_tostring(L, -2));
    }
    if (bidx <= 0) {
      lua_pop(L, 1);
      continue;
    }
    int rc = SQLITE_OK;
    switch (lua_type(L, -1)) {
      case LUA_TNUMBER:
        if (lua_isinteger(L, -1)) {
          rc = sqlite3_bind_int64(stmt, bidx, lua_tointeger(L, -1));
        } else {
          rc = sqlite3_bind_double(stmt, bidx, lua_tonumber(L, -1));
        }
        break;
      case LUA_TBOOLEAN:
        rc = sqlite3_bind_int(stmt, bidx, lua_toboolean(L, -1) ? 1 : 0);
        break;
      case LUA_TSTRING: {
        size_t len = 0;
        const char* s = lua_tolstring(L, -1, &len);
        rc = sqlite3_bind_text(stmt, bidx, s, static_cast<int>(len),
                               SQLITE_TRANSIENT);
        break;
      }
      case LUA_TNIL:
        rc = sqlite3_bind_null(stmt, bidx);
        break;
      default:
        LOG(WARNING) << "sqlite3: unable to bind Lua value (type "
                     << lua_typename(L, lua_type(L, -1)) << ")";
        break;
    }
    if (rc != SQLITE_OK) {
      *err = std::string("sqlite3: sqlite3_bind_* failed (") +
             sqlite3_errmsg(sqlite3_db_handle(stmt)) + ")";
      lua_pop(L, 2);
      return false;
    }
    lua_pop(L, 1);
  }
  return true;
}

// 跑一条已绑定的语句，把结果推到栈上（rows 表或 nil）。失败返回 false。
bool StepAll(lua_State* L, sqlite3_stmt* stmt) {
  int rc = sqlite3_step(stmt);
  int ncol = sqlite3_column_count(stmt);
  if (rc != SQLITE_DONE && rc != SQLITE_ROW) {
    return false;
  }
  if (ncol > 0) {
    lua_newtable(L);
  } else {
    lua_pushnil(L);
  }
  lua_Integer rows = 0;
  while (rc == SQLITE_ROW) {
    lua_newtable(L);
    for (int i = 0; i < ncol; ++i) {
      switch (sqlite3_column_type(stmt, i)) {
        case SQLITE_INTEGER:
          lua_pushinteger(L, sqlite3_column_int64(stmt, i));
          lua_setfield(L, -2, sqlite3_column_name(stmt, i));
          break;
        case SQLITE_FLOAT:
          lua_pushnumber(L, sqlite3_column_double(stmt, i));
          lua_setfield(L, -2, sqlite3_column_name(stmt, i));
          break;
        case SQLITE_TEXT:
        case SQLITE_BLOB: {
          const void* blob = sqlite3_column_blob(stmt, i);
          int bytes = sqlite3_column_bytes(stmt, i);
          lua_pushlstring(L, static_cast<const char*>(blob ? blob : ""),
                          blob ? bytes : 0);
          lua_setfield(L, -2, sqlite3_column_name(stmt, i));
          break;
        }
        default:
          break;
      }
    }
    lua_rawseti(L, -2, ++rows);
    rc = sqlite3_step(stmt);
  }
  if (rc != SQLITE_DONE) {
    lua_pop(L, 1);
    return false;
  }
  return true;
}

int SqliteExec(lua_State* L) {
  SqliteDb* d = CheckDb(L, 1);
  const char* sql = luaL_checkstring(L, 2);
  if (!lua_isnoneornil(L, 3)) {
    luaL_checktype(L, 3, LUA_TTABLE);
  }
  int top = lua_gettop(L);
  const char* tail = sql;
  int results = 0;
  while (tail && *tail) {
    sqlite3_stmt* stmt = nullptr;
    const char* next = nullptr;
    if (sqlite3_prepare_v2(d->db, tail, -1, &stmt, &next) != SQLITE_OK) {
      lua_settop(L, top);
      return luaL_error(L, "sqlite3: statement compilation failed (%s)",
                        sqlite3_errmsg(d->db));
    }
    tail = next;
    if (!stmt) {
      continue;  // 纯空白/注释
    }
    std::string err;
    if (!BindAll(L, stmt, 3, &err)) {
      sqlite3_finalize(stmt);
      lua_settop(L, top);
      return luaL_error(L, "%s", err.c_str());
    }
    // 只保留最后一条语句的结果
    lua_settop(L, top);
    results = 0;
    if (!StepAll(L, stmt)) {
      std::string msg = sqlite3_errmsg(d->db);
      sqlite3_finalize(stmt);
      lua_settop(L, top);
      return luaL_error(L, "sqlite3: exec error (%s)", msg.c_str());
    }
    results = 1;
    sqlite3_finalize(stmt);
  }
  if (!results) {
    lua_pushnil(L);
  }
  return 1;
}

int SqlitePrepare(lua_State* L) {
  SqliteDb* d = CheckDb(L, 1);
  const char* sql = luaL_checkstring(L, 2);
  sqlite3_stmt* stmt = nullptr;
  if (sqlite3_prepare_v2(d->db, sql, -1, &stmt, nullptr) != SQLITE_OK ||
      !stmt) {
    return luaL_error(L, "sqlite3: statement compilation failed (%s)",
                      sqlite3_errmsg(d->db));
  }
  int id = g_next_stmt_id++;
  Stmts()[id] = stmt;
  lua_pushinteger(L, id);
  return 1;
}

int SqliteStmtExec(lua_State* L) {
  int id = static_cast<int>(luaL_checkinteger(L, 1));
  auto it = Stmts().find(id);
  if (it == Stmts().end()) {
    return luaL_error(L, "sqlite3: statement finalized");
  }
  sqlite3_stmt* stmt = it->second;
  sqlite3_reset(stmt);
  sqlite3_clear_bindings(stmt);
  std::string err;
  if (!BindAll(L, stmt, 2, &err)) {
    return luaL_error(L, "%s", err.c_str());
  }
  if (!StepAll(L, stmt)) {
    std::string msg = sqlite3_errmsg(sqlite3_db_handle(stmt));
    sqlite3_reset(stmt);
    return luaL_error(L, "sqlite3: exec error (%s)", msg.c_str());
  }
  sqlite3_reset(stmt);
  return 1;
}

int SqliteStmtFree(lua_State* L) {
  int id = static_cast<int>(luaL_checkinteger(L, 1));
  auto it = Stmts().find(id);
  if (it != Stmts().end()) {
    sqlite3_finalize(it->second);
    Stmts().erase(it);
  }
  return 0;
}

// ===== URI =====

bool HasScheme(const std::string& s) {
  size_t i = 0;
  if (s.empty() || !isalpha(static_cast<unsigned char>(s[0]))) {
    return false;
  }
  for (i = 1; i < s.size(); ++i) {
    char c = s[i];
    if (c == ':') {
      return true;
    }
    if (!isalnum(static_cast<unsigned char>(c)) && c != '+' && c != '-' &&
        c != '.') {
      return false;
    }
  }
  return false;
}

void SetIfNonEmpty(lua_State* L, const char* key, std::string_view v) {
  if (!v.empty()) {
    lua_pushlstring(L, v.data(), v.size());
    lua_setfield(L, -2, key);
  }
}

// soup.parse_uri：没写 scheme 默认补 http://；解析失败返回 nil。
int UriParse(lua_State* L) {
  size_t len = 0;
  const char* s = luaL_checklstring(L, 1, &len);
  std::string str(s, len);
  if (str.empty()) {
    return 0;
  }
  if (!HasScheme(str)) {
    str = "http://" + str;
  }
  GURL url(str);
  if (!url.is_valid()) {
    return 0;
  }
  lua_newtable(L);
  SetIfNonEmpty(L, "scheme", url.scheme());
  SetIfNonEmpty(L, "user", url.username());
  SetIfNonEmpty(L, "password", url.password());
  SetIfNonEmpty(L, "host", url.host());
  SetIfNonEmpty(L, "path", url.path());
  SetIfNonEmpty(L, "query", url.query());
  SetIfNonEmpty(L, "fragment", url.ref());
  int port = url.IntPort();
  if (port > 0) {
    lua_pushinteger(L, port);
    lua_setfield(L, -2, "port");
  }
  return 1;
}

bool IsUnreserved(unsigned char c) {
  return isalnum(c) || c == '-' || c == '.' || c == '_' || c == '~';
}

// luakit.uri_encode(str [, allowed])：g_uri_escape_string 语义
int UriEncode(lua_State* L) {
  size_t len = 0;
  const char* s = luaL_checklstring(L, 1, &len);
  std::string allowed = luaL_optstring(L, 2, "");
  std::string out;
  out.reserve(len * 3);
  static const char kHex[] = "0123456789ABCDEF";
  for (size_t i = 0; i < len; ++i) {
    unsigned char c = static_cast<unsigned char>(s[i]);
    if (IsUnreserved(c) || allowed.find(static_cast<char>(c)) != std::string::npos) {
      out.push_back(static_cast<char>(c));
    } else {
      out.push_back('%');
      out.push_back(kHex[c >> 4]);
      out.push_back(kHex[c & 0xF]);
    }
  }
  lua_pushlstring(L, out.data(), out.size());
  return 1;
}

int HexVal(char c) {
  if (c >= '0' && c <= '9') {
    return c - '0';
  }
  if (c >= 'a' && c <= 'f') {
    return c - 'a' + 10;
  }
  if (c >= 'A' && c <= 'F') {
    return c - 'A' + 10;
  }
  return -1;
}

// luakit.uri_decode(str [, illegal])：g_uri_unescape_string 语义，
// 解出 illegal 里的字符或 NUL / 畸形转义时返回 nil, err。
int UriDecode(lua_State* L) {
  size_t len = 0;
  const char* s = luaL_checklstring(L, 1, &len);
  std::string illegal = luaL_optstring(L, 2, "");
  std::string out;
  out.reserve(len);
  for (size_t i = 0; i < len; ++i) {
    char c = s[i];
    if (c == '%') {
      if (i + 2 >= len) {
        return PushNilErr(L, "Illegal percent-encoding in URI");
      }
      int hi = HexVal(s[i + 1]);
      int lo = HexVal(s[i + 2]);
      if (hi < 0 || lo < 0) {
        return PushNilErr(L, "Illegal percent-encoding in URI");
      }
      char d = static_cast<char>((hi << 4) | lo);
      if (d == '\0' || illegal.find(d) != std::string::npos) {
        return PushNilErr(L, "Illegal character in URI");
      }
      out.push_back(d);
      i += 2;
    } else {
      out.push_back(c);
    }
  }
  lua_pushlstring(L, out.data(), out.size());
  return 1;
}

// ===== 进程 =====

void OnSpawnExit(int cb_id, base::Process process) {
  int exit_code = -1;
  bool ok = process.WaitForExit(&exit_code);
  std::string reason = ok ? "exit" : "unknown";
  if (ok && exit_code < 0) {
    reason = "signal";
  }
  LemurXLuakitDispatch("spawn", cb_id, reason, exit_code);
}

// __luakit.spawn(argv_table, cb_id) -> pid | nil, err
int Spawn(lua_State* L) {
  std::vector<std::string> argv = ReadStringList(L, 1);
  int cb_id = static_cast<int>(luaL_optinteger(L, 2, 0));
  if (argv.empty()) {
    return PushNilErr(L, "spawn: empty command");
  }
  base::LaunchOptions options;
  base::Process process = base::LaunchProcess(argv, options);
  if (!process.IsValid()) {
    return PushNilErr(L, "spawn: failed to launch " + argv[0]);
  }
  lua_pushinteger(L, process.Pid());
  base::ThreadPool::PostTask(
      FROM_HERE, {base::MayBlock(), base::TaskPriority::BEST_EFFORT},
      base::BindOnce(&OnSpawnExit, cb_id, std::move(process)));
  return 1;
}

// __luakit.spawn_sync(argv_table) -> status, stdout, stderr
// 阻塞 Lua 线程；stderr 与 stdout 合并回在第二个返回值里（Android 上
// base 只给合并输出），第三个返回值恒为 ""。
int SpawnSync(lua_State* L) {
  std::vector<std::string> argv = ReadStringList(L, 1);
  if (argv.empty()) {
    lua_pushinteger(L, -1);
    lua_pushliteral(L, "");
    lua_pushliteral(L, "spawn_sync: empty command");
    return 3;
  }
  base::CommandLine cl(argv);
  std::string output;
  int exit_code = -1;
  base::GetAppOutputWithExitCode(cl, &output, &exit_code);
  lua_pushinteger(L, exit_code);
  lua_pushlstring(L, output.data(), output.size());
  lua_pushliteral(L, "");
  return 3;
}

// ===== LuaFileSystem 子集（rc.lua / window.lua / styles 等 require "lfs"）=====

const char* ModeName(mode_t m) {
  if (S_ISREG(m)) {
    return "file";
  }
  if (S_ISDIR(m)) {
    return "directory";
  }
  if (S_ISLNK(m)) {
    return "link";
  }
  if (S_ISSOCK(m)) {
    return "socket";
  }
  if (S_ISFIFO(m)) {
    return "named pipe";
  }
  if (S_ISCHR(m)) {
    return "char device";
  }
  if (S_ISBLK(m)) {
    return "block device";
  }
  return "other";
}

std::string PermString(mode_t m) {
  std::string p(9, '-');
  const char* rwx = "rwxrwxrwx";
  for (int i = 0; i < 9; ++i) {
    if (m & (1 << (8 - i))) {
      p[i] = rwx[i];
    }
  }
  return p;
}

int PushStat(lua_State* L, const struct stat& st, const char* field) {
  if (field) {
    std::string f = field;
    if (f == "mode") {
      lua_pushstring(L, ModeName(st.st_mode));
    } else if (f == "size") {
      lua_pushinteger(L, st.st_size);
    } else if (f == "modification") {
      lua_pushinteger(L, st.st_mtime);
    } else if (f == "access") {
      lua_pushinteger(L, st.st_atime);
    } else if (f == "change") {
      lua_pushinteger(L, st.st_ctime);
    } else if (f == "permissions") {
      lua_pushstring(L, PermString(st.st_mode).c_str());
    } else if (f == "nlink") {
      lua_pushinteger(L, st.st_nlink);
    } else if (f == "uid") {
      lua_pushinteger(L, st.st_uid);
    } else if (f == "gid") {
      lua_pushinteger(L, st.st_gid);
    } else if (f == "ino") {
      lua_pushinteger(L, st.st_ino);
    } else if (f == "dev") {
      lua_pushinteger(L, st.st_dev);
    } else {
      return luaL_error(L, "invalid attribute name '%s'", field);
    }
    return 1;
  }
  lua_newtable(L);
  lua_pushstring(L, ModeName(st.st_mode));
  lua_setfield(L, -2, "mode");
  lua_pushinteger(L, st.st_size);
  lua_setfield(L, -2, "size");
  lua_pushinteger(L, st.st_mtime);
  lua_setfield(L, -2, "modification");
  lua_pushinteger(L, st.st_atime);
  lua_setfield(L, -2, "access");
  lua_pushinteger(L, st.st_ctime);
  lua_setfield(L, -2, "change");
  lua_pushstring(L, PermString(st.st_mode).c_str());
  lua_setfield(L, -2, "permissions");
  lua_pushinteger(L, st.st_nlink);
  lua_setfield(L, -2, "nlink");
  lua_pushinteger(L, st.st_uid);
  lua_setfield(L, -2, "uid");
  lua_pushinteger(L, st.st_gid);
  lua_setfield(L, -2, "gid");
  lua_pushinteger(L, st.st_ino);
  lua_setfield(L, -2, "ino");
  lua_pushinteger(L, st.st_dev);
  lua_setfield(L, -2, "dev");
  return 1;
}

int LfsAttributes(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  const char* field = luaL_optstring(L, 2, nullptr);
  struct stat st;
  if (stat(path, &st) != 0) {
    lua_pushnil(L);
    lua_pushfstring(L, "cannot obtain information from file '%s': %s", path,
                    strerror(errno));
    lua_pushinteger(L, errno);
    return 3;
  }
  return PushStat(L, st, field);
}

int LfsSymlinkAttributes(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  const char* field = luaL_optstring(L, 2, nullptr);
  struct stat st;
  if (lstat(path, &st) != 0) {
    lua_pushnil(L);
    lua_pushfstring(L, "cannot obtain information from file '%s': %s", path,
                    strerror(errno));
    lua_pushinteger(L, errno);
    return 3;
  }
  return PushStat(L, st, field);
}

// 返回目录项名字数组（含 "." 和 ".."，和 lfs.dir 迭代器一致）；Lua 内核包成迭代器。
int LfsDirList(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  DIR* dir = opendir(path);
  if (!dir) {
    return luaL_error(L, "cannot open %s: %s", path, strerror(errno));
  }
  lua_newtable(L);
  lua_Integer i = 0;
  while (struct dirent* ent = readdir(dir)) {
    lua_pushstring(L, ent->d_name);
    lua_rawseti(L, -2, ++i);
  }
  closedir(dir);
  return 1;
}

int LfsMkdir(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  if (mkdir(path, 0755) != 0) {
    lua_pushnil(L);
    lua_pushstring(L, strerror(errno));
    lua_pushinteger(L, errno);
    return 3;
  }
  lua_pushboolean(L, 1);
  return 1;
}

int LfsRmdir(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  if (rmdir(path) != 0) {
    lua_pushnil(L);
    lua_pushstring(L, strerror(errno));
    lua_pushinteger(L, errno);
    return 3;
  }
  lua_pushboolean(L, 1);
  return 1;
}

int LfsChdir(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  if (chdir(path) != 0) {
    lua_pushnil(L);
    lua_pushfstring(L, "Unable to change working directory to '%s'\n%s\n",
                    path, strerror(errno));
    return 2;
  }
  lua_pushboolean(L, 1);
  return 1;
}

int LfsCurrentDir(lua_State* L) {
  char buf[4096];
  if (!getcwd(buf, sizeof(buf))) {
    lua_pushnil(L);
    lua_pushstring(L, strerror(errno));
    return 2;
  }
  lua_pushstring(L, buf);
  return 1;
}

int LfsTouch(lua_State* L) {
  const char* path = luaL_checkstring(L, 1);
  FILE* f = fopen(path, "a");
  if (!f) {
    lua_pushnil(L);
    lua_pushstring(L, strerror(errno));
    return 2;
  }
  fclose(f);
  lua_pushboolean(L, 1);
  return 1;
}

// ===== 杂项 =====

// luakit.time()：自进程启动的秒数（含小数）
int Time(lua_State* L) {
  lua_pushnumber(L, (base::TimeTicks::Now() - StartTicks()).InSecondsF());
  return 1;
}

// __luakit.post(id)：在 Lua 线程下一轮投递 __luakit_dispatch("post", id)。
// idle_add 用它实现"空闲时反复调用"。
int Post(lua_State* L) {
  int id = static_cast<int>(luaL_checkinteger(L, 1));
  LemurXEngine::Get()->RunOnLuaThread(
      base::BindOnce(&LemurXLuakitDispatch, "post", id, std::string(), 0));
  return 0;
}

void PushValue(lua_State* L, const base::Value& value) {
  switch (value.type()) {
    case base::Value::Type::BOOLEAN:
      lua_pushboolean(L, value.GetBool());
      break;
    case base::Value::Type::INTEGER:
      lua_pushinteger(L, value.GetInt());
      break;
    case base::Value::Type::DOUBLE:
      lua_pushnumber(L, value.GetDouble());
      break;
    case base::Value::Type::STRING:
      lua_pushlstring(L, value.GetString().data(), value.GetString().size());
      break;
    case base::Value::Type::DICT:
      lua_newtable(L);
      for (const auto& item : value.GetDict()) {
        PushValue(L, item.second);
        lua_setfield(L, -2, item.first.c_str());
      }
      break;
    case base::Value::Type::LIST: {
      lua_newtable(L);
      lua_Integer i = 1;
      for (const auto& child : value.GetList()) {
        PushValue(L, child);
        lua_rawseti(L, -2, i++);
      }
      break;
    }
    default:
      lua_pushnil(L);
      break;
  }
}

// __luakit.env() -> table：filesDir/cacheDir/外部目录/版本/包名等，
// Java 侧 LemurXBridge.luakitEnv() 以 JSON 提供，这里解成 Lua 表。
int Env(lua_State* L) {
  // JNI 生成头只能被 lemurx_api.cc 包含一次（里面带 Java_J_N_* 存根定义），
  // 所以经由它导出的 LemurXLuakitEnvJson() 取 Java 侧 JSON。
  std::string s = LemurXLuakitEnvJson();
  if (s.empty()) {
    lua_newtable(L);
    return 1;
  }
  std::optional<base::Value> value = base::JSONReader::Read(s, base::JSON_PARSE_RFC);
  if (!value || !value->is_dict()) {
    lua_newtable(L);
    return 1;
  }
  PushValue(L, *value);
  return 1;
}

// __luakit.json_decode(str) -> value | nil, err ；json_encode(value) -> str
// luakit 自带模块（session/unique_instance/adblock 等）不需要 JSON，
// 但内核跟 lemurx.* 交换复杂数据时用它。
base::Value LuaToValue(lua_State* L, int index) {
  index = lua_absindex(L, index);
  switch (lua_type(L, index)) {
    case LUA_TBOOLEAN:
      return base::Value(lua_toboolean(L, index) != 0);
    case LUA_TNUMBER:
      if (lua_isinteger(L, index)) {
        return base::Value(static_cast<double>(lua_tointeger(L, index)));
      }
      return base::Value(lua_tonumber(L, index));
    case LUA_TSTRING: {
      size_t len = 0;
      const char* s = lua_tolstring(L, index, &len);
      return base::Value(std::string(s, len));
    }
    case LUA_TTABLE: {
      lua_Integer n = luaL_len(L, index);
      bool is_array = n > 0;
      if (is_array) {
        base::ListValue list;
        for (lua_Integer i = 1; i <= n; ++i) {
          lua_rawgeti(L, index, i);
          list.Append(LuaToValue(L, -1));
          lua_pop(L, 1);
        }
        return base::Value(std::move(list));
      }
      base::DictValue dict;
      lua_pushnil(L);
      while (lua_next(L, index) != 0) {
        if (lua_type(L, -2) == LUA_TSTRING) {
          dict.Set(lua_tostring(L, -2), LuaToValue(L, -1));
        }
        lua_pop(L, 1);
      }
      return base::Value(std::move(dict));
    }
    default:
      return base::Value();
  }
}

int JsonDecode(lua_State* L) {
  size_t len = 0;
  const char* s = luaL_checklstring(L, 1, &len);
  std::optional<base::Value> value = base::JSONReader::Read(std::string(s, len), base::JSON_PARSE_RFC);
  if (!value) {
    return PushNilErr(L, "invalid json");
  }
  PushValue(L, *value);
  return 1;
}

int JsonEncode(lua_State* L) {
  std::string out;
  base::JSONWriter::Write(LuaToValue(L, 1), &out);
  lua_pushlstring(L, out.data(), out.size());
  return 1;
}

int Getpid(lua_State* L) {
  lua_pushinteger(L, getpid());
  return 1;
}

// __luakit.widget(op, id, args_table) → value | true ；失败 → nil, err
// 直通 Java LemurXWidgetHost（luakit widgets/*.c 的 Android 宿主）。
int WidgetOp(lua_State* L) {
  const char* op = luaL_checkstring(L, 1);
  int id = static_cast<int>(luaL_optinteger(L, 2, 0));
  std::string json = "{}";
  if (lua_istable(L, 3)) {
    base::JSONWriter::Write(LuaToValue(L, 3), &json);
  }
  std::string out = LemurXLuakitWidgetOp(op, id, json);
  std::optional<base::Value> value = base::JSONReader::Read(out, base::JSON_PARSE_RFC);
  if (!value || !value->is_dict()) {
    return PushNilErr(L, "widget host unavailable");
  }
  const base::DictValue& dict = value->GetDict();
  if (!dict.FindBool("ok").value_or(false)) {
    const std::string* err = dict.FindString("error");
    return PushNilErr(L, err ? err->c_str() : "widget op failed");
  }
  const base::Value* v = dict.Find("value");
  if (!v) {
    lua_pushboolean(L, 1);
    return 1;
  }
  PushValue(L, *v);
  return 1;
}

void DispatchOnLuaThread(std::string kind, int id, std::string a, int b) {
  LemurXEngine* engine = LemurXEngine::Get();
  lua_State* L = engine->state();
  if (!L) {
    return;
  }
  LemurXEngine::ScopedState scope(engine, L, true);
  lua_getglobal(L, "__luakit_dispatch");
  if (!lua_isfunction(L, -1)) {
    lua_pop(L, 1);
    return;
  }
  lua_pushlstring(L, kind.data(), kind.size());
  lua_pushinteger(L, id);
  lua_pushlstring(L, a.data(), a.size());
  lua_pushinteger(L, b);
  if (lua_pcall(L, 4, 0, 0) != LUA_OK) {
    LOG(ERROR) << "luakit dispatch error: "
               << (lua_tostring(L, -1) ? lua_tostring(L, -1) : "?");
    lua_pop(L, 1);
  }
}

}  // namespace

void LemurXLuakitDispatch(const std::string& kind,
                         int id,
                         const std::string& a,
                         int b) {
  LemurXEngine::Get()->RunOnLuaThread(
      base::BindOnce(&DispatchOnLuaThread, kind, id, a, b));
}

namespace {

void DispatchWithReplyOnLuaThread(
    std::string kind,
    int id,
    std::string a,
    base::OnceCallback<void(const std::string&)> reply) {
  std::string result;
  LemurXEngine* engine = LemurXEngine::Get();
  lua_State* L = engine->state();
  if (L) {
    LemurXEngine::ScopedState scope(engine, L, true);
    lua_getglobal(L, "__luakit_dispatch_sync");
    if (lua_isfunction(L, -1)) {
      lua_pushlstring(L, kind.data(), kind.size());
      lua_pushinteger(L, id);
      lua_pushlstring(L, a.data(), a.size());
      if (lua_pcall(L, 3, 1, 0) != LUA_OK) {
        LOG(ERROR) << "luakit sync dispatch error: "
                   << (lua_tostring(L, -1) ? lua_tostring(L, -1) : "?");
      } else if (lua_isstring(L, -1)) {
        size_t len = 0;
        const char* s = lua_tolstring(L, -1, &len);
        result.assign(s, len);
      } else if (lua_isboolean(L, -1)) {
        result = lua_toboolean(L, -1) ? "true" : "false";
      }
      lua_pop(L, 1);
    } else {
      lua_pop(L, 1);
    }
  }
  std::move(reply).Run(result);
}

}  // namespace

void LemurXLuakitDispatchWithReply(
    const std::string& kind,
    int id,
    const std::string& a,
    base::OnceCallback<void(const std::string&)> reply) {
  // 引擎已被用户关掉：RunOnLuaThread 会静默丢任务，等回话的一方（Java 侧的
  // 同步按键 / 控件裁决）必须立刻拿到「空裁决」而不是等到超时。
  if (!LemurXEngine::Get()->enabled()) {
    std::move(reply).Run(std::string());
    return;
  }
  LemurXEngine::Get()->RunOnLuaThread(base::BindOnce(
      &DispatchWithReplyOnLuaThread, kind, id, a, std::move(reply)));
}

void RegisterLemurXLuakitNative(lua_State* L) {
  StartTicks();
  lua_newtable(L);

  SetFn(L, "regex_compile", RegexCompile);
  SetFn(L, "regex_match", RegexMatch);
  SetFn(L, "regex_free", RegexFree);

  SetFn(L, "sqlite_open", SqliteOpen);
  SetFn(L, "sqlite_close", SqliteClose);
  SetFn(L, "sqlite_exec", SqliteExec);
  SetFn(L, "sqlite_changes", SqliteChanges);
  SetFn(L, "sqlite_prepare", SqlitePrepare);
  SetFn(L, "sqlite_stmt_exec", SqliteStmtExec);
  SetFn(L, "sqlite_stmt_free", SqliteStmtFree);

  SetFn(L, "widget", WidgetOp);

  SetFn(L, "uri_parse", UriParse);
  SetFn(L, "uri_encode", UriEncode);
  SetFn(L, "uri_decode", UriDecode);

  SetFn(L, "spawn", Spawn);
  SetFn(L, "spawn_sync", SpawnSync);
  SetFn(L, "getpid", Getpid);

  SetFn(L, "lfs_attributes", LfsAttributes);
  SetFn(L, "lfs_symlinkattributes", LfsSymlinkAttributes);
  SetFn(L, "lfs_dir", LfsDirList);
  SetFn(L, "lfs_mkdir", LfsMkdir);
  SetFn(L, "lfs_rmdir", LfsRmdir);
  SetFn(L, "lfs_chdir", LfsChdir);
  SetFn(L, "lfs_currentdir", LfsCurrentDir);
  SetFn(L, "lfs_touch", LfsTouch);

  SetFn(L, "time", Time);
  SetFn(L, "post", Post);
  SetFn(L, "env", Env);
  SetFn(L, "json_decode", JsonDecode);
  SetFn(L, "json_encode", JsonEncode);

  lua_setglobal(L, "__luakit");
}
