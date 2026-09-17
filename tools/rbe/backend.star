# -*- bazel-starlark -*-
# Copyright 2026 The LemurX Authors
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
"""Siso backend config for the LemurX self-hosted REAPI (Buildbarn) cluster.

Installed into chromium/src/build/config/siso/backend_config/backend.star by
the `configure_siso` gclient hook via the `reapi_backend_config_path`
custom_var in chromium/.gclient (an absolute path to this file).
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
