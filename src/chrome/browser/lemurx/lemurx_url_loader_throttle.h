// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_LEMURX_LEMURX_URL_LOADER_THROTTLE_H_
#define CHROME_BROWSER_LEMURX_LEMURX_URL_LOADER_THROTTLE_H_

#include "base/memory/weak_ptr.h"
#include "services/network/public/mojom/fetch_api.mojom-shared.h"
#include "third_party/blink/public/common/loader/url_loader_throttle.h"

class LemurXURLLoaderThrottle : public blink::URLLoaderThrottle {
 public:
  LemurXURLLoaderThrottle();
  ~LemurXURLLoaderThrottle() override;

  LemurXURLLoaderThrottle(const LemurXURLLoaderThrottle&) = delete;
  LemurXURLLoaderThrottle& operator=(const LemurXURLLoaderThrottle&) =
      delete;

  void DetachFromCurrentSequence() override;
  void WillStartRequest(network::ResourceRequest* request,
                        bool* defer) override;
  void WillProcessResponse(const GURL& response_url,
                           network::mojom::URLResponseHead* response_head,
                           bool* defer) override;

 private:
  void DeferredCancelWithError(int error_code);

  network::mojom::RequestDestination destination_ =
      network::mojom::RequestDestination::kEmpty;
  base::WeakPtrFactory<LemurXURLLoaderThrottle> weak_factory_{this};
};

#endif  // CHROME_BROWSER_LEMURX_LEMURX_URL_LOADER_THROTTLE_H_
