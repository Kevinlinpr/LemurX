// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/lemurx/lemurx_url_loader_throttle.h"

#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "base/functional/bind.h"
#include "base/memory/weak_ptr.h"
#include "base/task/single_thread_task_runner.h"
#include "chrome/browser/lemurx/lemurx_net_rules.h"
#include "mojo/public/cpp/bindings/pending_receiver.h"
#include "mojo/public/cpp/bindings/pending_remote.h"
#include "mojo/public/cpp/bindings/receiver.h"
#include "mojo/public/cpp/bindings/remote.h"
#include "mojo/public/cpp/bindings/self_owned_receiver.h"
#include "mojo/public/cpp/system/data_pipe.h"
#include "mojo/public/cpp/system/data_pipe_drainer.h"
#include "mojo/public/cpp/system/data_pipe_producer.h"
#include "mojo/public/cpp/system/string_data_source.h"
#include "net/base/net_errors.h"
#include "net/base/request_priority.h"
#include "net/http/http_request_headers.h"
#include "net/http/http_response_headers.h"
#include "net/url_request/redirect_info.h"
#include "services/network/public/cpp/data_element.h"
#include "services/network/public/cpp/resource_request.h"
#include "services/network/public/cpp/resource_request_body.h"
#include "services/network/public/cpp/url_loader_completion_status.h"
#include "services/network/public/mojom/early_hints.mojom.h"
#include "services/network/public/mojom/fetch_api.mojom-shared.h"
#include "services/network/public/mojom/url_loader.mojom.h"
#include "services/network/public/mojom/url_response_head.mojom.h"
#include "url/gurl.h"

namespace {

constexpr size_t kMaxRewriteBodyBytes = 2 * 1024 * 1024;
constexpr size_t kHardMaxBodyBytes = 8 * 1024 * 1024;

class LemurXBodyRewriter : public network::mojom::URLLoaderClient,
                             public network::mojom::URLLoader,
                             public mojo::DataPipeDrainer::Client {
 public:
  LemurXBodyRewriter(
      std::vector<std::pair<std::string, std::string>> replacements,
      mojo::PendingRemote<network::mojom::URLLoaderClient>
          destination_url_loader_client)
      : replacements_(std::move(replacements)),
        destination_url_loader_client_(
            std::move(destination_url_loader_client)) {}
  ~LemurXBodyRewriter() override = default;

  bool Start(
      mojo::PendingRemote<network::mojom::URLLoader> source_url_loader_remote,
      mojo::PendingReceiver<network::mojom::URLLoaderClient>
          source_url_client_receiver,
      mojo::ScopedDataPipeConsumerHandle body,
      mojo::ScopedDataPipeProducerHandle producer_handle) {
    source_url_loader_.Bind(std::move(source_url_loader_remote));
    source_url_client_receiver_.Bind(std::move(source_url_client_receiver));
    data_drainer_ =
        std::make_unique<mojo::DataPipeDrainer>(this, std::move(body));
    producer_handle_ = std::move(producer_handle);
    return true;
  }

  void OnReceiveEarlyHints(network::mojom::EarlyHintsPtr early_hints) override {
  }
  void OnReceiveResponse(
      network::mojom::URLResponseHeadPtr response_head,
      mojo::ScopedDataPipeConsumerHandle body,
      std::optional<mojo_base::BigBuffer> cached_metadata) override {}
  void OnReceiveRedirect(
      const net::RedirectInfo& redirect_info,
      network::mojom::URLResponseHeadPtr response_head) override {}
  void OnUploadProgress(int64_t current_position,
                        int64_t total_size,
                        OnUploadProgressCallback ack_callback) override {}
  void OnTransferSizeUpdated(int32_t transfer_size_diff) override {
    destination_url_loader_client_->OnTransferSizeUpdated(transfer_size_diff);
  }
  void OnComplete(const network::URLLoaderCompletionStatus& status) override {
    original_complete_status_ = status;
    MaybeSendOnComplete();
  }

  void FollowRedirect(
      const std::vector<std::string>& removed_headers,
      const net::HttpRequestHeaders& modified_headers,
      const net::HttpRequestHeaders& modified_cors_exempt_headers,
      const std::optional<GURL>& new_url) override {}
  void SetPriority(net::RequestPriority priority,
                   int32_t intra_priority_value) override {
    if (source_url_loader_) {
      source_url_loader_->SetPriority(priority, intra_priority_value);
    }
  }
  void PauseReadingBodyFromNet() override {
    if (source_url_loader_) {
      source_url_loader_->PauseReadingBodyFromNet();
    }
  }
  void ResumeReadingBodyFromNet() override {
    if (source_url_loader_) {
      source_url_loader_->ResumeReadingBodyFromNet();
    }
  }

  void OnDataAvailable(const void* data, size_t num_bytes) override {
    if (data_.size() >= kHardMaxBodyBytes) {
      skip_replace_ = true;
      return;
    }
    size_t to_copy = num_bytes;
    if (data_.size() + to_copy > kHardMaxBodyBytes) {
      to_copy = kHardMaxBodyBytes - data_.size();
      skip_replace_ = true;
    }
    if (data_.size() + num_bytes > kMaxRewriteBodyBytes) {
      skip_replace_ = true;
    }
    data_.append(static_cast<const char*>(data), to_copy);
  }
  void OnDataComplete() override {
    data_drainer_.reset();
    if (!skip_replace_ && !data_.empty()) {
      LemurXNetRules::ApplyBodyReplacements(&data_, replacements_);
    }

    auto data_producer =
        std::make_unique<mojo::DataPipeProducer>(std::move(producer_handle_));
    auto data = std::make_unique<std::string>(std::move(data_));
    auto source = std::make_unique<mojo::StringDataSource>(
        *data, mojo::StringDataSource::AsyncWritingMode::
                   STRING_STAYS_VALID_UNTIL_COMPLETION);
    mojo::DataPipeProducer* producer = data_producer.get();
    producer->Write(
        std::move(source),
        base::BindOnce(
            [](std::unique_ptr<mojo::DataPipeProducer> producer,
               std::unique_ptr<std::string> data,
               base::OnceCallback<void(MojoResult)> done, MojoResult result) {
              std::move(done).Run(result);
            },
            std::move(data_producer), std::move(data),
            base::BindOnce(&LemurXBodyRewriter::OnDataWritten,
                           weak_factory_.GetWeakPtr())));
  }

 private:
  void OnDataWritten(MojoResult result) {
    data_write_result_ = result;
    MaybeSendOnComplete();
  }

  void MaybeSendOnComplete() {
    if (!original_complete_status_ || !data_write_result_) {
      return;
    }
    if (*data_write_result_ != MOJO_RESULT_OK) {
      destination_url_loader_client_->OnComplete(
          network::URLLoaderCompletionStatus(net::ERR_INSUFFICIENT_RESOURCES));
      return;
    }
    destination_url_loader_client_->OnComplete(*original_complete_status_);
  }

  std::vector<std::pair<std::string, std::string>> replacements_;
  bool skip_replace_ = false;
  std::unique_ptr<mojo::DataPipeDrainer> data_drainer_;
  mojo::ScopedDataPipeProducerHandle producer_handle_;
  std::string data_;
  std::optional<network::URLLoaderCompletionStatus> original_complete_status_;
  std::optional<MojoResult> data_write_result_;
  mojo::Receiver<network::mojom::URLLoaderClient> source_url_client_receiver_{
      this};
  mojo::Remote<network::mojom::URLLoader> source_url_loader_;
  mojo::Remote<network::mojom::URLLoaderClient> destination_url_loader_client_;
  base::WeakPtrFactory<LemurXBodyRewriter> weak_factory_{this};
};

}  // namespace

LemurXURLLoaderThrottle::LemurXURLLoaderThrottle() = default;
LemurXURLLoaderThrottle::~LemurXURLLoaderThrottle() = default;

void LemurXURLLoaderThrottle::DetachFromCurrentSequence() {}

void LemurXURLLoaderThrottle::DeferredCancelWithError(int error_code) {
  if (delegate_) {
    delegate_->CancelWithError(error_code, "LemurX");
  }
}

void LemurXURLLoaderThrottle::WillStartRequest(
    network::ResourceRequest* request,
    bool* defer) {
  destination_ = request->destination;
  std::optional<LemurXNetRules::Rule> rule =
      LemurXNetRules::Get()->FindMatch(request->url, request->destination);
  if (!rule) {
    return;
  }

  for (const auto& header : rule->request_headers) {
    request->headers.SetHeader(header.first, header.second);
  }
  for (const auto& name : rule->remove_request_headers) {
    request->headers.RemoveHeader(name);
  }

  if (rule->NeedsRequestBodyRewrite()) {
    if (!rule->request_body.empty()) {
      request->request_body = network::ResourceRequestBody::CreateFromBytes(
          rule->request_body.data(), rule->request_body.size());
    } else if (request->request_body && request->request_body->elements()) {
      std::string body;
      bool all_bytes = true;
      for (const auto& element : *request->request_body->elements()) {
        if (element.type() != network::DataElement::Tag::kBytes) {
          all_bytes = false;
          break;
        }
        const auto& bytes = element.As<network::DataElementBytes>();
        body.append(bytes.AsStringPiece().data(), bytes.AsStringPiece().size());
      }
      if (all_bytes) {
        LemurXNetRules::ApplyBodyReplacements(&body,
                                                rule->replace_request_body);
        request->request_body = network::ResourceRequestBody::CreateFromBytes(
            body.data(), body.size());
      }
    }
  }

  if (rule->action == LemurXNetRules::Action::kRedirect &&
      rule->redirect_url.is_valid()) {
    request->url = rule->redirect_url;
    return;
  }

  if (rule->action == LemurXNetRules::Action::kBlock && delegate_) {
    delegate_->CancelWithError(net::ERR_BLOCKED_BY_CLIENT, "LemurX");
  }
}

void LemurXURLLoaderThrottle::WillProcessResponse(
    const GURL& response_url,
    network::mojom::URLResponseHead* response_head,
    bool* defer) {
  if (!response_head || !response_head->headers) {
    return;
  }
  std::optional<LemurXNetRules::Rule> rule =
      LemurXNetRules::Get()->FindMatch(response_url, destination_);
  if (!rule) {
    return;
  }
  for (const auto& name : rule->remove_response_headers) {
    response_head->headers->RemoveHeader(name);
  }
  for (const auto& header : rule->response_headers) {
    response_head->headers->SetHeader(header.first, header.second);
  }

  if (!rule->NeedsBodyRewrite() ||
      !LemurXNetRules::IsRewritableMime(response_head->mime_type) ||
      !delegate_) {
    return;
  }
  if (response_head->content_length >
      static_cast<int64_t>(kMaxRewriteBodyBytes)) {
    return;
  }

  response_head->headers->RemoveHeader("Content-Length");
  response_head->content_length = -1;

  mojo::ScopedDataPipeConsumerHandle body;
  mojo::ScopedDataPipeProducerHandle producer_handle;
  MojoResult create_pipe_result =
      mojo::CreateDataPipe(/*options=*/nullptr, producer_handle, body);
  if (create_pipe_result != MOJO_RESULT_OK) {
    *defer = true;
    base::SingleThreadTaskRunner::GetCurrentDefault()->PostTask(
        FROM_HERE,
        base::BindOnce(&LemurXURLLoaderThrottle::DeferredCancelWithError,
                       weak_factory_.GetWeakPtr(),
                       net::ERR_INSUFFICIENT_RESOURCES));
    return;
  }

  mojo::PendingRemote<network::mojom::URLLoader> new_remote;
  mojo::PendingRemote<network::mojom::URLLoaderClient> url_loader_client;
  mojo::PendingReceiver<network::mojom::URLLoaderClient> new_receiver =
      url_loader_client.InitWithNewPipeAndPassReceiver();
  mojo::PendingRemote<network::mojom::URLLoader> source_loader;
  mojo::PendingReceiver<network::mojom::URLLoaderClient> source_client_receiver;

  auto loader = std::make_unique<LemurXBodyRewriter>(
      rule->replace_body, std::move(url_loader_client));
  LemurXBodyRewriter* loader_ptr = loader.get();
  mojo::MakeSelfOwnedReceiver(std::move(loader),
                              new_remote.InitWithNewPipeAndPassReceiver());
  delegate_->InterceptResponse(std::move(new_remote), std::move(new_receiver),
                               &source_loader, &source_client_receiver, &body);
  if (!body) {
    return;
  }
  loader_ptr->Start(std::move(source_loader),
                    std::move(source_client_receiver), std::move(body),
                    std::move(producer_handle));
}
