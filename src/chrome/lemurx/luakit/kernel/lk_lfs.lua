-- lfs：LuaFileSystem 子集（rc.lua / window.lua / styles / userscripts / adblock 用到的）
--   attributes symlinkattributes dir mkdir rmdir chdir currentdir touch
-- 底层是 __luakit.lfs_*（POSIX stat/opendir）。

local N = __luakit
local M = {}

M.attributes = N.lfs_attributes
M.symlinkattributes = N.lfs_symlinkattributes
M.mkdir = N.lfs_mkdir
M.rmdir = N.lfs_rmdir
M.chdir = N.lfs_chdir
M.currentdir = N.lfs_currentdir
M.touch = N.lfs_touch

-- lfs.dir(path) 返回迭代器：for name in lfs.dir(p) do ... end；含 "." ".."
function M.dir(path)
    local list = N.lfs_dir(path)
    local i = 0
    local iter = {
        next = function()
            i = i + 1
            return list[i]
        end,
        close = function() end,
    }
    return function()
        i = i + 1
        return list[i]
    end, iter
end

function M.setmode() return true, "binary" end
function M.lock() return true end
function M.unlock() return true end

package.loaded["lfs"] = M
_G.lfs = M
return M
