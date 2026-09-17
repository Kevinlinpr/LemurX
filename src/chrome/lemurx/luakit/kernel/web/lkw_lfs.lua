-- lfs（渲染进程版）：luakit 的 web 进程能直接用 LuaFileSystem；Chromium 渲染进程在
-- 沙箱里没有文件系统，这里给 lousy.uri 等模块一个行为一致的空实现
-- （attributes 一律 nil,"sandboxed"，dir 空迭代，写操作失败）。

local M = {}

local function sandboxed() return nil, "renderer sandbox: no filesystem access" end

M.attributes = sandboxed
M.symlinkattributes = sandboxed
M.mkdir = sandboxed
M.rmdir = sandboxed
M.chdir = sandboxed
M.touch = sandboxed
M.link = sandboxed
M.setmode = function() return "binary" end
M.currentdir = function() return "/" end
M.dir = function()
    local iter = function() return nil end
    return iter, { next = iter, close = function() end }
end
M.lock = sandboxed
M.unlock = sandboxed
M.lock_dir = sandboxed

package.preload["lfs"] = function() return M end
package.loaded["lfs"] = M
_G.lfs = M
return M
