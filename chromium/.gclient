solutions = [
  {
    "name": "src",
    "url": "https://chromium.googlesource.com/chromium/src.git@154.0.8037.49",
    "managed": False,
    "custom_deps": {},
    "custom_vars": {
      "checkout_pgo_profiles": True,
      # Optional: point these at your own REAPI cluster, then set
      # use_remoteexec=true in GN args. Defaults are local-only so a public
      # clone does not try to reach a private builder.
      # "reapi_address": "reapi.example:8980",
      # "reapi_instance": "default",
      # "reapi_backend_config_path": "/absolute/path/to/lemurx/tools/rbe/backend.star",
    },
  },
]
target_os = ["android"]
