// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// 无障碍树快照 → JSON，给 Java 设备桥（LemurXAx.java / LemurXDeviceBridge.java）。
//
// 这是 LemurX agent 读页面的唯一通道：一次性的 AXTreeSnapshot 由渲染进程在自己的
// 无障碍对象缓存上序列化，不往页面里注入任何脚本，也不挂 DevTools——页面能观察到的
// 只有和普通 Chrome 完全相同的行为。坐标是 CSS 像素、相对根框架的（已减去根滚动），
// 换算到屏幕由 Java 侧用 RenderCoordinates 完成。

#include <jni.h>

#include <string>
#include <string_view>
#include <utility>

#include "base/android/callback_android.h"
#include "base/android/jni_android.h"
#include "base/android/scoped_java_ref.h"
#include "base/base64.h"
#include "base/functional/bind.h"
#include "base/json/json_writer.h"
#include "base/strings/utf_string_conversions.h"
#include "base/time/time.h"
#include "base/values.h"
#include "components/viz/common/frame_sinks/copy_output_result.h"
#include "content/public/browser/render_widget_host_view.h"
#include "content/public/browser/web_contents.h"
#include "third_party/skia/include/core/SkBitmap.h"
#include "ui/accessibility/ax_enum_util.h"
#include "ui/accessibility/ax_enums.mojom.h"
#include "ui/accessibility/ax_mode.h"
#include "ui/accessibility/ax_node.h"
#include "ui/accessibility/ax_node_data.h"
#include "ui/accessibility/ax_role_properties.h"
#include "ui/accessibility/ax_tree.h"
#include "ui/accessibility/ax_tree_update.h"
#include "ui/gfx/codec/jpeg_codec.h"
#include "ui/gfx/geometry/rect.h"
#include "ui/gfx/geometry/rect_f.h"
#include "ui/gfx/geometry/size.h"

// Must come after all headers that specialize FromJniType() / ToJniType().
#include "chrome/android/chrome_jni_headers/LemurXAx_jni.h"

using base::android::JavaRef;
using base::android::ScopedJavaGlobalRef;

namespace {

constexpr size_t kMaxElements = 600;
constexpr size_t kMaxText = 120 * 1024;

bool IsBlockDisplay(std::string_view display) {
  return display.empty() || display == "inline" || display == "inline-block" ||
                 display == "inline-flex" || display == "inline-grid" ||
                 display == "contents" || display == "inline-table"
             ? false
             : true;
}

void AppendNewline(std::u16string* text) {
  if (text->empty() || text->back() == u'\n') {
    return;
  }
  text->push_back(u'\n');
}

void AppendText(std::u16string* text, std::u16string_view piece) {
  if (piece.empty() || text->size() >= kMaxText) {
    return;
  }
  if (!text->empty() && text->back() != u'\n' && text->back() != u' ' &&
      piece.front() != u' ') {
    text->push_back(u' ');
  }
  text->append(piece);
}

// 这个节点是不是 agent 可能想点 / 想填 / 想定位的。
bool IsInteresting(const ui::AXNode* node) {
  ax::mojom::Role role = node->GetRole();
  const ui::AXNodeData& data = node->data();
  if (ui::IsControl(role) || ui::IsLink(role) || ui::IsHeading(role) ||
      ui::IsTextField(role) || ui::IsImage(role) || ui::IsMenuItem(role) ||
      ui::IsItemLike(role)) {
    return true;
  }
  if (data.HasState(ax::mojom::State::kEditable) ||
      data.HasState(ax::mojom::State::kFocusable)) {
    return true;
  }
  return data.HasAction(ax::mojom::Action::kDoDefault) &&
         !data.GetStringAttribute(ax::mojom::StringAttribute::kName).empty();
}

void Walk(const ui::AXTree& tree,
          const ui::AXNode* node,
          int depth,
          int32_t focus_id,
          base::ListValue* elements,
          std::u16string* text) {
  const ui::AXNodeData& data = node->data();
  ax::mojom::Role role = node->GetRole();
  std::string display =
      data.GetStringAttribute(ax::mojom::StringAttribute::kDisplay);
  bool block = IsBlockDisplay(display) || ui::IsHeading(role) ||
               role == ax::mojom::Role::kParagraph ||
               role == ax::mojom::Role::kListItem ||
               role == ax::mojom::Role::kRow ||
               role == ax::mojom::Role::kTable;
  if (block) {
    AppendNewline(text);
  }

  std::string name = data.GetStringAttribute(ax::mojom::StringAttribute::kName);
  std::string value = data.GetStringAttribute(ax::mojom::StringAttribute::kValue);

  if (role == ax::mojom::Role::kStaticText) {
    AppendText(text, base::UTF8ToUTF16(name));
    // InlineTextBox 子节点是同一段文字的拆分，不再往下走。
  } else if (role == ax::mojom::Role::kLineBreak) {
    AppendNewline(text);
  } else {
    bool leaf = node->GetUnignoredChildCount() == 0;
    if (IsInteresting(node) && elements->size() < kMaxElements) {
      bool offscreen = false;
      // 不裁剪：视口外的元素也要知道真实位置（裁剪后只剩贴在容器边上的一条零高矩形，
      // 没法算要滚多远）。offscreen 标记与裁剪无关，照样算。
      gfx::RectF bounds =
          tree.GetTreeBounds(node, &offscreen, /*clip_bounds=*/false);
      base::DictValue e;
      e.Set("id", node->id());
      e.Set("role", ui::ToString(role));
      if (!name.empty()) {
        e.Set("name", name);
      }
      if (!value.empty()) {
        e.Set("value", value);
      }
      std::string placeholder =
          data.GetStringAttribute(ax::mojom::StringAttribute::kPlaceholder);
      if (!placeholder.empty()) {
        e.Set("placeholder", placeholder);
      }
      std::string url = data.GetStringAttribute(ax::mojom::StringAttribute::kUrl);
      if (!url.empty()) {
        e.Set("url", url);
      }
      std::string tag =
          data.GetStringAttribute(ax::mojom::StringAttribute::kHtmlTag);
      if (!tag.empty()) {
        e.Set("tag", tag);
      }
      std::string input_type =
          data.GetStringAttribute(ax::mojom::StringAttribute::kInputType);
      if (!input_type.empty()) {
        e.Set("inputType", input_type);
      }
      base::ListValue rect;
      rect.Append(bounds.x());
      rect.Append(bounds.y());
      rect.Append(bounds.width());
      rect.Append(bounds.height());
      e.Set("rect", std::move(rect));
      if (offscreen) {
        e.Set("offscreen", true);
      }
      if (data.HasState(ax::mojom::State::kEditable)) {
        e.Set("editable", true);
      }
      if (node->id() == focus_id) {
        e.Set("focused", true);
      }
      if (data.GetRestriction() == ax::mojom::Restriction::kDisabled) {
        e.Set("disabled", true);
      }
      if (data.HasState(ax::mojom::State::kExpanded)) {
        e.Set("expanded", true);
      }
      if (data.HasBoolAttribute(ax::mojom::BoolAttribute::kSelected)) {
        e.Set("selected",
              data.GetBoolAttribute(ax::mojom::BoolAttribute::kSelected));
      }
      switch (data.GetCheckedState()) {
        case ax::mojom::CheckedState::kTrue:
          e.Set("checked", true);
          break;
        case ax::mojom::CheckedState::kFalse:
          e.Set("checked", false);
          break;
        case ax::mojom::CheckedState::kMixed:
          e.Set("checked", "mixed");
          break;
        default:
          break;
      }
      elements->Append(std::move(e));
    }
    // 控件的可读文字：名字（和值）也进正文，agent 读到的和看到的一致。名字来自子节点内容
    // （按钮、链接的文字）或页面上可见的 <label> 时不重复追加——那段文字自己会被走到。
    ax::mojom::NameFrom name_from = data.GetNameFrom();
    bool name_visible_elsewhere =
        name_from == ax::mojom::NameFrom::kContents ||
        name_from == ax::mojom::NameFrom::kRelatedElement;
    bool collapsed_menu = (role == ax::mojom::Role::kComboBoxSelect ||
                           role == ax::mojom::Role::kPopUpButton) &&
                          !data.HasState(ax::mojom::State::kExpanded);
    if (leaf || ui::IsTextField(role) || ui::IsControl(role)) {
      if (!name.empty() && role != ax::mojom::Role::kImage &&
          (!name_visible_elsewhere || (leaf && !ui::IsTextField(role)))) {
        AppendText(text, base::UTF8ToUTF16(name));
      }
      if (!value.empty() && value != name) {
        AppendText(text, base::UTF8ToUTF16("[" + value + "]"));
      } else if (value.empty() && ui::IsTextField(role)) {
        std::string placeholder =
            data.GetStringAttribute(ax::mojom::StringAttribute::kPlaceholder);
        if (!placeholder.empty()) {
          AppendText(text, base::UTF8ToUTF16("[" + placeholder + "]"));
        }
      }
    }
    // 文本框里的字已经作为 value 给出；收起的下拉框不展开它的选项（选项在弹层里再报）。
    if (!ui::IsTextField(role) && !collapsed_menu) {
      for (auto it = node->UnignoredChildrenBegin();
           it != node->UnignoredChildrenEnd(); ++it) {
        Walk(tree, it.get(), depth + 1, focus_id, elements, text);
      }
    }
  }
  if (block) {
    AppendNewline(text);
  }
}

void OnSnapshot(ScopedJavaGlobalRef<jobject> callback,
                ui::AXTreeUpdate& update) {
  base::DictValue out;
  out.Set("nodes", static_cast<int>(update.nodes.size()));
  if (!update.nodes.empty()) {
    ui::AXTree tree;
    if (!tree.Unserialize(update) || !tree.root()) {
      // 留给 Java 侧打日志；页面看不到。
      out.Set("error", tree.error());
    } else {
      const ui::AXNode* root = tree.root();
      base::ListValue elements;
      std::u16string text;
      Walk(tree, root, 0, update.tree_data.focus_id, &elements, &text);
      out.Set("ok", true);
      out.Set("url", update.tree_data.url);
      out.Set("title", update.tree_data.title);
      out.Set("focusId", update.tree_data.focus_id);
      out.Set("scrollX", root->GetIntAttribute(ax::mojom::IntAttribute::kScrollX));
      out.Set("scrollY", root->GetIntAttribute(ax::mojom::IntAttribute::kScrollY));
      gfx::RectF root_bounds = tree.GetTreeBounds(root);
      base::ListValue rb;
      rb.Append(root_bounds.x());
      rb.Append(root_bounds.y());
      rb.Append(root_bounds.width());
      rb.Append(root_bounds.height());
      out.Set("rootRect", std::move(rb));
      out.Set("truncated", text.size() >= kMaxText);
      out.Set("text", text);
      out.Set("elements", std::move(elements));
    }
  }
  if (!out.contains("ok")) {
    out.Set("ok", false);
  }
  std::string json;
  base::JSONWriter::Write(out, &json);
  base::android::RunStringCallbackAndroid(callback, json);
}

}  // namespace

static void JNI_LemurXAx_Snapshot(JNIEnv* env,
                                  content::WebContents* web_contents,
                                  jint max_nodes,
                                  const JavaRef<jobject>& j_callback) {
  if (!web_contents) {
    base::android::RunStringCallbackAndroid(j_callback, "{\"ok\":false}");
    return;
  }
  web_contents->RequestAXTreeSnapshot(
      base::BindOnce(&OnSnapshot, ScopedJavaGlobalRef<jobject>(j_callback)),
      ui::AXMode(ui::kAXModeComplete.flags() | ui::AXMode::kHTML),
      static_cast<size_t>(max_nodes > 0 ? max_nodes : 5000), base::Seconds(2),
      content::WebContents::AXTreeSnapshotPolicy::kAll);
}

// ---------------------------------------------------------------- 截图（合成器拷贝输出）

namespace {

void OnCapture(ScopedJavaGlobalRef<jobject> callback,
               int quality,
               const content::CopyFromSurfaceResult& result) {
  std::string out;
  if (result.has_value() && !result->bitmap.drawsNothing()) {
    std::optional<std::vector<uint8_t>> jpeg =
        gfx::JPEGCodec::Encode(result->bitmap, quality);
    if (jpeg) {
      out = base::Base64Encode(*jpeg);
    }
  }
  base::android::RunStringCallbackAndroid(callback, out);
}

}  // namespace

// 网页视口的一帧：viz 从合成器的输出里拷一份（和标签缩略图同一条路），不经过渲染进程、
// 不触发任何页面事件，Android 视图（agent 的手指 / 键盘 / 横幅）也不在里面。
// 回调收到 JPEG 的 base64；拿不到就是空串。
static void JNI_LemurXAx_Capture(JNIEnv* env,
                                 content::WebContents* web_contents,
                                 jint quality,
                                 const JavaRef<jobject>& j_callback) {
  content::RenderWidgetHostView* view =
      web_contents ? web_contents->GetRenderWidgetHostView() : nullptr;
  if (!view) {
    base::android::RunStringCallbackAndroid(j_callback, std::string());
    return;
  }
  view->CopyFromSurface(
      gfx::Rect(), gfx::Size(), base::Seconds(2),
      base::BindOnce(&OnCapture, ScopedJavaGlobalRef<jobject>(j_callback),
                     static_cast<int>(quality)));
}

DEFINE_JNI_FOR_LemurXAx()
