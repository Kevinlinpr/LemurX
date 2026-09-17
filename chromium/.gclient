solutions = [
  {
    "name": "src",
    "url": "https://chromium.googlesource.com/chromium/src.git@154.0.8037.49",
    "managed": False,
    "custom_deps": {},
    "custom_vars": {
      "checkout_pgo_profiles": True,
      # Self-hosted REAPI (Buildbarn) cluster for distributed builds.
      # Pairs with use_remoteexec=true / use_siso=true in tools/args.gn.
      # The backend config lives in this repo (tools/rbe/backend.star); the
      # configure_siso hook copies it into build/config/siso/backend_config/.
      "reapi_address": "192.168.0.18:8980",
      "reapi_instance": "default",
      "reapi_backend_config_path": "/home/user/code/lemurx/tools/rbe/backend.star",
    },
  },
]
target_os = ["android"]
