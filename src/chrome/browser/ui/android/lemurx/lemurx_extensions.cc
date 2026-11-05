// Copyright 2026 The LemurX Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// 插件管理页的原生侧：列出已安装扩展、开关、固定、移除、开发者模式。
// 卸载确认也从这里弹出。手机上的标签模态框会被 Aero 自己的窗口盖住，
// 所以确认框走 Activity 上的对话框，不走 ModalDialogManager。

#include <jni.h>

#include <algorithm>
#include <string>
#include <utility>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/scoped_java_ref.h"
#include "base/functional/callback.h"
#include "base/strings/utf_string_conversions.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/ui/toolbar/toolbar_actions_model.h"
#include "chrome/common/pref_names.h"
#include "components/prefs/pref_service.h"
#include "extensions/browser/disable_reason.h"
#include "extensions/browser/extension_registrar.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/browser/extension_system.h"
#include "extensions/browser/extension_util.h"
#include "extensions/browser/image_loader.h"
#include "extensions/browser/renderer_startup_helper.h"
#include "extensions/browser/uninstall_reason.h"
#include "extensions/browser/user_script_manager.h"
#include "extensions/common/extension.h"
#include "extensions/common/extension_set.h"
#include "extensions/common/features/feature_developer_mode_only.h"
#include "extensions/common/manifest.h"
#include "extensions/common/manifest_handlers/description_info.h"
#include "extensions/common/manifest_handlers/icons_handler.h"
#include "third_party/skia/include/core/SkBitmap.h"
#include "ui/android/window_android.h"
#include "ui/gfx/android/java_bitmap.h"
#include "ui/gfx/geometry/size.h"
#include "ui/gfx/image/image.h"

// Must come after all headers that specialize FromJniType() / ToJniType().
#include "chrome/android/chrome_jni_headers/LemurXExtensions_jni.h"

namespace {

struct UninstallPrompt {
  base::OnceCallback<void(bool)> callback;
};

UninstallPrompt* g_uninstall_prompt = nullptr;

void OnIconLoaded(int64_t token, std::string id, const gfx::Image& image) {
  if (image.IsEmpty()) {
    return;
  }
  const SkBitmap& bitmap = image.AsBitmap();
  if (bitmap.drawsNothing()) {
    return;
  }
  JNIEnv* env = base::android::AttachCurrentThread();
  Java_LemurXExtensions_icon(env, token, id, bitmap);
}

void LoadIcon(Profile* profile,
              const extensions::Extension* extension,
              int64_t token) {
  extensions::ExtensionResource resource =
      extensions::IconsInfo::GetIconResource(
          extension, 64, ExtensionIconSet::Match::kSmaller);
  if (resource.empty()) {
    return;
  }
  extensions::ImageLoader* loader = extensions::ImageLoader::Get(profile);
  if (!loader) {
    return;
  }
  loader->LoadImageAsync(extension, resource, gfx::Size(64, 64),
                         base::BindOnce(&OnIconLoaded, token, extension->id()));
}

void ApplyDeveloperMode(Profile* profile, bool enabled) {
  profile->GetPrefs()->SetBoolean(prefs::kExtensionsUIDeveloperMode, enabled);
  extensions::SetCurrentDeveloperMode(
      extensions::util::GetBrowserContextId(profile), enabled);
  if (auto* helper =
          extensions::RendererStartupHelperFactory::GetForBrowserContext(
              profile)) {
    helper->OnDeveloperModeChanged(enabled);
  }
  if (extensions::ExtensionSystem* system =
          extensions::ExtensionSystem::Get(profile)) {
    if (extensions::UserScriptManager* scripts = system->user_script_manager()) {
      scripts->SetUserScriptSourceEnabledForExtensions(
          extensions::UserScript::Source::kDynamicUserScript, enabled);
    }
  }
}

}  // namespace

namespace lemurx {

void ShowExtensionUninstallPrompt(
    ui::WindowAndroid* window,
    const std::u16string& title,
    const std::u16string& message,
    const std::u16string& ok,
    const std::u16string& cancel,
    base::OnceCallback<void(bool)> callback) {
  if (g_uninstall_prompt) {
    UninstallPrompt* previous = g_uninstall_prompt;
    g_uninstall_prompt = nullptr;
    std::move(previous->callback).Run(false);
    delete previous;
  }
  g_uninstall_prompt = new UninstallPrompt{std::move(callback)};
  JNIEnv* env = base::android::AttachCurrentThread();
  const std::string title_utf8 = base::UTF16ToUTF8(title);
  const std::string message_utf8 = base::UTF16ToUTF8(message);
  const std::string ok_utf8 = base::UTF16ToUTF8(ok);
  const std::string cancel_utf8 = base::UTF16ToUTF8(cancel);
  const int64_t prompt = reinterpret_cast<int64_t>(g_uninstall_prompt);
  if (window) {
    Java_LemurXExtensions_showUninstall(env, window->GetJavaObject(), title_utf8,
                                        message_utf8, ok_utf8, cancel_utf8,
                                        prompt);
  } else {
    Java_LemurXExtensions_showUninstall(
        env, base::android::ScopedJavaLocalRef<jobject>(), title_utf8,
        message_utf8, ok_utf8, cancel_utf8, prompt);
  }
}

void DismissExtensionUninstallPrompt() {
  if (!g_uninstall_prompt) {
    return;
  }
  delete g_uninstall_prompt;
  g_uninstall_prompt = nullptr;
  Java_LemurXExtensions_dismissUninstall(
      base::android::AttachCurrentThread());
}

}  // namespace lemurx

static void JNI_LemurXExtensions_Refresh(JNIEnv* env,
                                         Profile* profile,
                                         int64_t token) {
  if (!profile) {
    Java_LemurXExtensions_begin(env, token, false);
    Java_LemurXExtensions_end(env, token);
    return;
  }
  bool developer_mode =
      profile->GetPrefs()->GetBoolean(prefs::kExtensionsUIDeveloperMode);
  Java_LemurXExtensions_begin(env, token, developer_mode);

  extensions::ExtensionRegistry* registry =
      extensions::ExtensionRegistry::Get(profile);
  ToolbarActionsModel* toolbar = ToolbarActionsModel::Get(profile);
  std::vector<scoped_refptr<const extensions::Extension>> installed;
  if (registry) {
    for (const auto& extension : registry->GenerateInstalledExtensionsSet()) {
      if (!extension ||
          extensions::Manifest::IsComponentLocation(extension->location())) {
        continue;
      }
      installed.push_back(extension);
    }
  }
  std::sort(installed.begin(), installed.end(),
            [](const scoped_refptr<const extensions::Extension>& a,
               const scoped_refptr<const extensions::Extension>& b) {
              return a->name() < b->name();
            });
  for (const auto& extension : installed) {
    const bool enabled =
        registry->enabled_extensions().Contains(extension->id());
    const bool can_pin = toolbar && toolbar->HasAction(extension->id()) &&
                         !toolbar->IsActionForcePinned(extension->id());
    const bool pinned = toolbar && toolbar->IsActionPinned(extension->id());
    Java_LemurXExtensions_add(
        env, token, extension->id(), extension->name(),
        extensions::DescriptionInfo::GetDescription(*extension),
        extension->version().GetString(), enabled, extension->from_webstore(),
        can_pin, pinned);
  }
  Java_LemurXExtensions_end(env, token);
  for (const auto& extension : installed) {
    LoadIcon(profile, extension.get(), token);
  }
}

static bool JNI_LemurXExtensions_SetEnabled(JNIEnv* env,
                                            Profile* profile,
                                            const std::string& id,
                                            bool enabled) {
  if (!profile) {
    return false;
  }
  extensions::ExtensionRegistrar* registrar =
      extensions::ExtensionRegistrar::Get(profile);
  extensions::ExtensionRegistry* registry =
      extensions::ExtensionRegistry::Get(profile);
  if (!registrar || !registry || !registry->GetInstalledExtension(id)) {
    return false;
  }
  if (enabled) {
    registrar->EnableExtension(id);
  } else {
    registrar->DisableExtension(id, {extensions::disable_reason::DISABLE_USER_ACTION});
  }
  return true;
}

static bool JNI_LemurXExtensions_Uninstall(JNIEnv* env,
                                           Profile* profile,
                                           const std::string& id) {
  if (!profile) {
    return false;
  }
  extensions::ExtensionRegistrar* registrar =
      extensions::ExtensionRegistrar::Get(profile);
  if (!registrar) {
    return false;
  }
  std::u16string error;
  return registrar->UninstallExtension(
      id, extensions::UNINSTALL_REASON_USER_INITIATED, &error);
}

static bool JNI_LemurXExtensions_SetPinned(JNIEnv* env,
                                           Profile* profile,
                                           const std::string& id,
                                           bool pinned) {
  ToolbarActionsModel* toolbar =
      profile ? ToolbarActionsModel::Get(profile) : nullptr;
  if (!toolbar || !toolbar->HasAction(id) || toolbar->IsActionForcePinned(id)) {
    return false;
  }
  if (profile->IsOffTheRecord()) {
    return false;
  }
  if (toolbar->IsActionPinned(id) == pinned) {
    return true;
  }
  toolbar->SetActionVisibility(id, pinned);
  return true;
}

static bool JNI_LemurXExtensions_HasInstalled(JNIEnv* env, Profile* profile) {
  if (!profile) {
    return false;
  }
  extensions::ExtensionRegistry* registry =
      extensions::ExtensionRegistry::Get(profile);
  if (!registry) {
    return false;
  }
  for (const auto& extension : registry->GenerateInstalledExtensionsSet()) {
    if (extension &&
        !extensions::Manifest::IsComponentLocation(extension->location())) {
      return true;
    }
  }
  return false;
}

static void JNI_LemurXExtensions_SetDeveloperMode(JNIEnv* env,
                                                  Profile* profile,
                                                  bool enabled) {
  if (!profile) {
    return;
  }
  ApplyDeveloperMode(profile, enabled);
}

static void JNI_LemurXExtensions_OnUninstallChoice(JNIEnv* env,
                                                   int64_t prompt_ptr,
                                                   bool accepted) {
  auto* prompt = reinterpret_cast<UninstallPrompt*>(prompt_ptr);
  if (prompt != g_uninstall_prompt) {
    return;
  }
  g_uninstall_prompt = nullptr;
  base::OnceCallback<void(bool)> callback = std::move(prompt->callback);
  delete prompt;
  if (callback) {
    std::move(callback).Run(accepted);
  }
}

DEFINE_JNI_FOR_LemurXExtensions()
