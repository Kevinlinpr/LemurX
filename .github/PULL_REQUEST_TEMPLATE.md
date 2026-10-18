## Summary

-

## Kind of change

- [ ] `src/` overlay (new files)
- [ ] `patches/` hook into upstream
- [ ] Lua script / docs
- [ ] Build / repo hygiene

## Test plan

- [ ] `tools/apply.py` on the pinned Chromium tag
- [ ] Documented Lua APIs updated in `LUA_GUIDE.md` if the surface changed
- [ ] Privileged APIs stay unreachable from UGC Lua
- [ ] Master switch still tears down the new hook

## Notes

Default `tools/args.gn` must stay local-only and royalty-free codecs.
