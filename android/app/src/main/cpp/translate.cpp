// The translation engine, as the phone reaches it.
//
// The browser runs the published WebAssembly build of this same engine; this is the same
// library built native, so a word answered by a machine is answered by the same machine on
// both surfaces rather than by two that disagree.
//
// Nothing here decides what to translate. The core reports the words no dictionary could
// answer and the service passes those on, exactly as it does for the synthesiser.

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "translator/definitions.h"
#include "translator/response.h"
#include "translator/response_options.h"
#include "translator/service.h"
#include "translator/translation_model.h"

namespace {

using marian::bergamot::BlockingService;
using marian::bergamot::Response;
using marian::bergamot::ResponseOptions;
using marian::bergamot::TranslationModel;

std::mutex lock;
std::shared_ptr<BlockingService> service;
std::shared_ptr<TranslationModel> model;

std::string text_of(JNIEnv *env, jstring value) {
  if (value == nullptr) return {};
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string out(chars == nullptr ? "" : chars);
  if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
  return out;
}

}  // namespace

extern "C" {

/// Open a direction. The configuration names files this app has already fetched, so the model
/// is read from the filesystem rather than handed across as bytes.
JNIEXPORT jint JNICALL
Java_io_github_nanbiz_phonetix_core_Lex_translateOpen(JNIEnv *env, jclass, jstring config) {
  std::lock_guard<std::mutex> held(lock);
  const std::string yaml = text_of(env, config);
  try {
    if (!service) {
      // One worker: this is called from a background thread of ours and a pool inside a pool
      // is two schedulers arguing over one phone.
      BlockingService::Config settings;
      settings.cacheSize = 0;
      service = std::make_shared<BlockingService>(settings);
    }
    marian::bergamot::MemoryBundle memory;
    model = std::make_shared<TranslationModel>(yaml, std::move(memory), /*replicas=*/1);
    return 1;
  } catch (...) {
    // A model that will not open is a direction this reader cannot translate, which is an
    // ordinary answer and not a crash: the words stay as the dictionary left them.
    model.reset();
    return 0;
  }
}

/// Whether a direction is open, so nothing offers what it cannot do.
JNIEXPORT jint JNICALL
Java_io_github_nanbiz_phonetix_core_Lex_translateReady(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> held(lock);
  return (service && model) ? 1 : 0;
}

/// Translate a batch, in the order it was given.
///
/// One request per piece rather than one joined text: what comes back has to line up with the
/// words the core is waiting to fill, and a joined text comes back as a sentence nobody can
/// cut apart again at the boundaries it went in on.
JNIEXPORT jobjectArray JNICALL
Java_io_github_nanbiz_phonetix_core_Lex_translateSay(JNIEnv *env, jclass, jobjectArray texts) {
  jclass string_class = env->FindClass("java/lang/String");
  const jsize count = texts == nullptr ? 0 : env->GetArrayLength(texts);
  jobjectArray out = env->NewObjectArray(count, string_class, env->NewStringUTF(""));
  std::lock_guard<std::mutex> held(lock);
  if (!service || !model || count == 0) return out;

  std::vector<std::string> asked;
  asked.reserve(static_cast<size_t>(count));
  for (jsize at = 0; at < count; ++at) {
    auto item = reinterpret_cast<jstring>(env->GetObjectArrayElement(texts, at));
    asked.push_back(text_of(env, item));
    env->DeleteLocalRef(item);
  }

  try {
    // One set of options per piece: the engine takes a list as long as the batch.
    ResponseOptions one;
    one.alignment = false;
    one.qualityScores = false;
    std::vector<ResponseOptions> options(asked.size(), one);
    std::vector<Response> said = service->translateMultiple(model, std::move(asked), options);
    for (jsize at = 0; at < count && at < static_cast<jsize>(said.size()); ++at) {
      env->SetObjectArrayElement(out, at, env->NewStringUTF(said[at].target.text.c_str()));
    }
  } catch (...) {
    // Nothing translated is nothing filled; the words keep whatever the dictionary said.
  }
  return out;
}

}  // extern "C"
