# -*- bazel-starlark -*-
# Copyright 2026 The LemurX Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
"""Optional Siso backend config for a self-hosted REAPI (e.g. Buildbarn) cluster.

Not used by the default local build. To enable remote exec:

  1. Set use_remoteexec = true in your GN args.
  2. Uncomment reapi_* in chromium/.gclient. reapi_backend_config_path must
     be an *absolute* path to this file.
  3. gclient runhooks (or configure_siso.py) copies it into
     chromium/src/build/config/siso/backend_config/.

Adjust platform_properties for your workers.
"""

load("@builtin//struct.star", "module")

def __platform_properties(ctx):
    return {
        "default": {
            "OSFamily": "Linux",
            "Pool": "chromium-linux",
            "container-image": "docker://gcr.io/chops-public-images-prod/rbe/siso-chromium/linux@sha256:d7cb1ab14a0f20aa669c23f22c15a9dead761dcac19f43985bf9dd5f41fbef3a",
        },
        "large": {},
    }

backend = module("backend", platform_properties = __platform_properties)
