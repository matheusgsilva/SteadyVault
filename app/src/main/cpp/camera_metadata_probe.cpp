#include <jni.h>
#include <camera/NdkCameraMetadata.h>
#include <dlfcn.h>
#include <android/log.h>

#include <cstdint>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr const char* kLogTag = "SteadyVaultCapture";

struct ACameraManager;
using CreateManagerFn = ACameraManager* (*)();
using DeleteManagerFn = void (*)(ACameraManager*);
using GetCharacteristicsFn = camera_status_t (*)(ACameraManager*, const char*, ACameraMetadata**);
using FreeMetadataFn = void (*)(ACameraMetadata*);
using GetTagFromNameFn = camera_status_t (*)(const ACameraMetadata*, const char*, uint32_t*);
using GetConstEntryFn = camera_status_t (*)(const ACameraMetadata*, uint32_t, ACameraMetadata_const_entry*);

struct Api {
    void* handle = nullptr;
    CreateManagerFn createManager = nullptr;
    DeleteManagerFn deleteManager = nullptr;
    GetCharacteristicsFn getCharacteristics = nullptr;
    FreeMetadataFn freeMetadata = nullptr;
    GetTagFromNameFn getTagFromName = nullptr;
    GetConstEntryFn getConstEntry = nullptr;

    bool ready() const {
        return handle && createManager && deleteManager && getCharacteristics &&
            freeMetadata && getTagFromName && getConstEntry;
    }
};

Api loadApi() {
    Api api;
    api.handle = dlopen("libcamera2ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!api.handle) return api;

    api.createManager = reinterpret_cast<CreateManagerFn>(
        dlsym(api.handle, "ACameraManager_create"));
    api.deleteManager = reinterpret_cast<DeleteManagerFn>(
        dlsym(api.handle, "ACameraManager_delete"));
    api.getCharacteristics = reinterpret_cast<GetCharacteristicsFn>(
        dlsym(api.handle, "ACameraManager_getCameraCharacteristics"));
    api.freeMetadata = reinterpret_cast<FreeMetadataFn>(
        dlsym(api.handle, "ACameraMetadata_free"));
    api.getTagFromName = reinterpret_cast<GetTagFromNameFn>(
        dlsym(api.handle, "ACameraMetadata_getTagFromName"));
    api.getConstEntry = reinterpret_cast<GetConstEntryFn>(
        dlsym(api.handle, "ACameraMetadata_getConstEntry"));
    return api;
}

const char* typeName(uint8_t type) {
    switch (type) {
        case ACAMERA_TYPE_BYTE: return "BYTE";
        case ACAMERA_TYPE_INT32: return "INT32";
        case ACAMERA_TYPE_FLOAT: return "FLOAT";
        case ACAMERA_TYPE_INT64: return "INT64";
        case ACAMERA_TYPE_DOUBLE: return "DOUBLE";
        case ACAMERA_TYPE_RATIONAL: return "RATIONAL";
        default: return "UNKNOWN";
    }
}

template <typename T>
void appendValues(std::ostringstream& out, const T* values, uint32_t count) {
    const uint32_t limit = count > 16 ? 16 : count;
    out << "[";
    for (uint32_t i = 0; i < limit; ++i) {
        if (i) out << ",";
        out << values[i];
    }
    if (count > limit) out << ",...";
    out << "]";
}

std::string entryValue(const ACameraMetadata_const_entry& entry) {
    std::ostringstream out;
    switch (entry.type) {
        case ACAMERA_TYPE_BYTE: {
            out << "[";
            const uint32_t limit = entry.count > 24 ? 24 : entry.count;
            for (uint32_t i = 0; i < limit; ++i) {
                if (i) out << ",";
                out << static_cast<unsigned>(entry.data.u8[i]);
            }
            if (entry.count > limit) out << ",...";
            out << "]";
            break;
        }
        case ACAMERA_TYPE_INT32:
            appendValues(out, entry.data.i32, entry.count);
            break;
        case ACAMERA_TYPE_FLOAT:
            appendValues(out, entry.data.f, entry.count);
            break;
        case ACAMERA_TYPE_INT64:
            appendValues(out, entry.data.i64, entry.count);
            break;
        case ACAMERA_TYPE_DOUBLE:
            appendValues(out, entry.data.d, entry.count);
            break;
        case ACAMERA_TYPE_RATIONAL: {
            out << "[";
            const uint32_t limit = entry.count > 12 ? 12 : entry.count;
            for (uint32_t i = 0; i < limit; ++i) {
                if (i) out << ",";
                out << entry.data.r[i].numerator << "/" << entry.data.r[i].denominator;
            }
            if (entry.count > limit) out << ",...";
            out << "]";
            break;
        }
        default:
            out << "?";
    }
    return out.str();
}

jobjectArray toJavaArray(JNIEnv* env, const std::vector<std::string>& rows) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(rows.size()), stringClass, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(rows.size()); ++i) {
        jstring value = env->NewStringUTF(rows[i].c_str());
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

}  // namespace

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_steadyvault_camera_capture_service_NativeCameraMetadataProbe_nativeInspectCharacteristics(
    JNIEnv* env,
    jclass,
    jstring cameraId,
    jobjectArray names) {

    std::vector<std::string> rows;
    Api api = loadApi();

    if (!api.ready()) {
        std::ostringstream out;
        out << "ndkReady=false"
            << " lib=" << (api.handle ? "ok" : "missing")
            << " managerCreate=" << (api.createManager ? "ok" : "missing")
            << " managerDelete=" << (api.deleteManager ? "ok" : "missing")
            << " characteristics=" << (api.getCharacteristics ? "ok" : "missing")
            << " free=" << (api.freeMetadata ? "ok" : "missing")
            << " getTag=" << (api.getTagFromName ? "ok" : "missing")
            << " getEntry=" << (api.getConstEntry ? "ok" : "missing");
        rows.push_back(out.str());
        if (api.handle) dlclose(api.handle);
        return toJavaArray(env, rows);
    }

    const char* cameraIdChars = env->GetStringUTFChars(cameraId, nullptr);
    ACameraManager* manager = api.createManager();
    ACameraMetadata* metadata = nullptr;
    const camera_status_t characteristicsStatus =
        manager && cameraIdChars
            ? api.getCharacteristics(manager, cameraIdChars, &metadata)
            : ACAMERA_ERROR_INVALID_PARAMETER;

    if (cameraIdChars) env->ReleaseStringUTFChars(cameraId, cameraIdChars);

    if (characteristicsStatus != ACAMERA_OK || !metadata) {
        std::ostringstream out;
        out << "ndkReady=true metadata=false characteristicsStatus=" << characteristicsStatus;
        rows.push_back(out.str());
        if (manager) api.deleteManager(manager);
        dlclose(api.handle);
        return toJavaArray(env, rows);
    }

    const jsize size = names ? env->GetArrayLength(names) : 0;
    rows.reserve(static_cast<size_t>(size) + 1);
    rows.emplace_back("ndkReady=true metadata=true source=independent-ndk-characteristics");

    for (jsize i = 0; i < size; ++i) {
        auto nameObj = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        const char* nameChars = env->GetStringUTFChars(nameObj, nullptr);
        const std::string name = nameChars ? nameChars : "";

        uint32_t tag = 0;
        const camera_status_t tagStatus =
            api.getTagFromName(metadata, name.c_str(), &tag);

        std::ostringstream out;
        out << "key=" << name
            << " tagStatus=" << tagStatus;

        if (tagStatus == ACAMERA_OK) {
            out << " tag=0x"
                << std::hex << std::uppercase << tag << std::dec;

            ACameraMetadata_const_entry entry{};
            const camera_status_t entryStatus =
                api.getConstEntry(metadata, tag, &entry);
            out << " entryStatus=" << entryStatus;

            if (entryStatus == ACAMERA_OK) {
                out << " type=" << typeName(entry.type)
                    << "(" << static_cast<unsigned>(entry.type) << ")"
                    << " count=" << entry.count
                    << " value=" << entryValue(entry);
            } else {
                out << " type=UNKNOWN_ABSENT"
                    << " count=0"
                    << " note=tag-resolved-but-no-entry-in-static-metadata";
            }
        }

        rows.push_back(out.str());

        if (nameChars) env->ReleaseStringUTFChars(nameObj, nameChars);
        env->DeleteLocalRef(nameObj);
    }

    api.freeMetadata(metadata);
    api.deleteManager(manager);
    dlclose(api.handle);
    return toJavaArray(env, rows);
}
