LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := default-permissions_co.copperhead.updater.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/default-permissions
LOCAL_SRC_FILES := $(LOCAL_MODULE)
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := permissions_co.copperhead.updater.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/permissions
LOCAL_SRC_FILES := $(LOCAL_MODULE)
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := whitelist_co.copperhead.updater.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/sysconfig
LOCAL_SRC_FILES := $(LOCAL_MODULE)
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_USE_AAPT2 := true
LOCAL_PACKAGE_NAME := Updater
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_REQUIRED_MODULES := default-permissions_co.copperhead.updater.xml permissions_co.copperhead.updater.xml whitelist_co.copperhead.updater.xml
LOCAL_PRIVILEGED_MODULE := true

LOCAL_STATIC_ANDROID_LIBRARIES := \
    androidx.core_core \
	androidx.appcompat_appcompat \
	androidx.preference_preference \
	androidx.recyclerview_recyclerview

LOCAL_PRIVATE_PLATFORM_APIS := true
include $(BUILD_PACKAGE)
