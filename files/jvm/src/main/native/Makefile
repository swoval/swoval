TARGET_DIR := ../../../target
RESOURCES_DIR := ../resources/native
BUILD_DIR := $(TARGET_DIR)/build
NATIVE_DIR := native/$(shell uname -sm | cut -d ' ' -f2 | tr '[:upper:]' '[:lower:]')
LIB_DIR := ../resources/$(NATIVE_DIR)
LIB_NAME := swoval-files0
POSIX_LIB_NAME := lib$(LIB_NAME)
QUICKLIST_SOURCE := com_swoval_files_NativeDirectoryLister
WIN64CC := x86_64-w64-mingw32-g++
CC := clang

UNAME_S := $(shell uname -s)

ifeq ($(UNAME_S),Darwin)
	JNI_INCLUDE := -I$(shell mdfind -name jni.h | head -n 1 | xargs dirname)\
		-I$(shell mdfind -name jni_md.h | head -n 1 | xargs dirname)
	LD := /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/c++
	OBJS := $(BUILD_DIR)/x86_64/darwin/$(QUICKLIST_SOURCE).o \
		$(BUILD_DIR)/x86_64/darwin/com_swoval_files_FileEventMonitorImpl.o \
		$(BUILD_DIR)/x86_64/windows/$(QUICKLIST_SOURCE).o \

	LIBS := $(RESOURCES_DIR)/x86_64/$(POSIX_LIB_NAME).dylib \
		$(TARGET_DIR)/x86_64/$(LIB_NAME).dll \


endif

ifeq ($(UNAME_S), Linux)
	BASE_INCLUDE := $(shell locate jni.h | tail -n 1 | xargs dirname)
	JNI_INCLUDE := -I$(BASE_INCLUDE) -I$(BASE_INCLUDE)/linux
	OBJS := $(BUILD_DIR)/x86_64/linux/$(QUICKLIST_SOURCE).o \

	LIBS := $(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).so \

endif


ifeq ($(UNAME_S), FreeBSD)
	JNI_INCLUDE := -I$(JAVA_HOME)/include -I$(JAVA_HOME)/include/freebsd
	OBJS := $(BUILD_DIR)/x86_64/freebsd/$(QUICKLIST_SOURCE).o \

	LIBS := $(TARGET_DIR)/x86_64/freebsd/$(POSIX_LIB_NAME).so \

endif

LINUX_CCFLAGS := -fPIC
LINUX_LDFLAGS := -fPIC -shared

NPMDIR := ../../../../js/npm/src/
CCFLAGS := -I./include -I$(NPMDIR) $(JNI_INCLUDE) -Wno-unused-command-line-argument -std=c++11 -O3

all: $(LIBS)

.PHONY: clean all

$(BUILD_DIR)/x86_64/linux/$(QUICKLIST_SOURCE).o: posix/$(QUICKLIST_SOURCE).cc
	echo $(JNI_INCLUDE)
	mkdir -p $(BUILD_DIR)/x86_64/linux; \
	$(CC) -c $< $(CCFLAGS) $(JNI_INCLUDE) -fPIC -o $@

$(BUILD_DIR)/x86_64/freebsd/$(QUICKLIST_SOURCE).o: posix/$(QUICKLIST_SOURCE).cc
	mkdir -p $(BUILD_DIR)/x86_64/freebsd; \
	$(CC) -c $< $(CCFLAGS) $(JNI_INCLUDE) -fPIC -o $@

$(BUILD_DIR)/x86_64/windows/$(QUICKLIST_SOURCE).o: windows/$(QUICKLIST_SOURCE).cc
	mkdir -p $(BUILD_DIR)/x86_64/windows; \
	$(WIN64CC) -c $< $(CCFLAGS) -o $@ -D__WIN__

$(BUILD_DIR)/x86_64/darwin/$(QUICKLIST_SOURCE).o: posix/$(QUICKLIST_SOURCE).cc
	mkdir -p $(BUILD_DIR)/x86_64/darwin; \
	$(CC) -c $< $(CCFLAGS) -framework Carbon -o $@

$(BUILD_DIR)/arm64/darwin/$(QUICKLIST_SOURCE).o: posix/$(QUICKLIST_SOURCE).cc
	mkdir -p $(BUILD_DIR)/arm64/darwin; \
	$(CC) -c $< $(CCFLAGS) -target arm64-apple-macos11 --stdlib=libc++ -framework Carbon -o $@

$(BUILD_DIR)/x86_64/darwin/com_swoval_files_apple_FileEventMonitorImpl.o: $(NPMDIR)/swoval_apple_file_system.hpp
$(BUILD_DIR)/x86_64/darwin/com_swoval_files_apple_FileEventMonitorImpl.o: apple/com_swoval_files_apple_FileEventMonitorImpl.cc
	mkdir -p $(BUILD_DIR)/x86_64/darwin; \
	$(CC) -c $< $(CCFLAGS) -framework Carbon -o $@

$(BUILD_DIR)/arm64/darwin/com_swoval_files_apple_FileEventMonitorImpl.o: $(NPMDIR)/swoval_apple_file_system.hpp
$(BUILD_DIR)/arm64/darwin/com_swoval_files_apple_FileEventMonitorImpl.o: apple/com_swoval_files_apple_FileEventMonitorImpl.cc
	mkdir -p $(BUILD_DIR)/arm64/darwin; \
	$(CC) -c $< $(CCFLAGS) -target arm64-apple-macos11 -framework Carbon --stdlib=libc++ -o $@

$(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).dylib: $(BUILD_DIR)/x86_64/darwin/com_swoval_files_apple_FileEventMonitorImpl.o \
	$(BUILD_DIR)/x86_64/darwin/$(QUICKLIST_SOURCE).o 
	mkdir -p $(TARGET_DIR)/x86_64; \
	$(LD) -dynamiclib -framework Carbon $(CCFLAGS) -Wl,-headerpad_max_install_names -install_name @rpath/$(POSIX_LIB_NAME) \
	$(BUILD_DIR)/x86_64/darwin/com_swoval_files_apple_FileEventMonitorImpl.o \
	$(BUILD_DIR)/x86_64/darwin/$(QUICKLIST_SOURCE).o  \
	 -o $@

$(TARGET_DIR)/arm64/$(POSIX_LIB_NAME).dylib: $(BUILD_DIR)/arm64/darwin/com_swoval_files_apple_FileEventMonitorImpl.o \
	$(BUILD_DIR)/arm64/darwin/$(QUICKLIST_SOURCE).o 
	mkdir -p $(TARGET_DIR)/arm64; \
	$(CC) -arch arm64 -dynamiclib -framework Carbon -install_name @rpath/$(POSIX_LIB_NAME) \
	$(BUILD_DIR)/arm64/darwin/com_swoval_files_apple_FileEventMonitorImpl.o \
	$(BUILD_DIR)/arm64/darwin/$(QUICKLIST_SOURCE).o  \
	 -o $@ -lc++

$(RESOURCES_DIR)/x86_64/$(POSIX_LIB_NAME).dylib: $(TARGET_DIR)/arm64/$(POSIX_LIB_NAME).dylib $(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).dylib
	lipo -create -output $@ $^

$(TARGET_DIR)/x86_64/$(LIB_NAME).dll: $(BUILD_DIR)/x86_64/windows/$(QUICKLIST_SOURCE).o
	mkdir -p $(TARGET_DIR)/x86_64; \
		$(WIN64CC) $(LINUX_LDFLAGS) $< $(CCFLAGS) -Wl,-headerpad_max_install_names -o $@ \
		-D__WIN__ \
		-Wall -Wextra \
		-nostdlib -ffreestanding -mconsole -Os \
		-fno-stack-check -fno-stack-protector -mno-stack-arg-probe \
		-fno-leading-underscore \
		-lkernel32;
	mkdir -p ../resources/native/x86_64; \
	cp $(TARGET_DIR)/x86_64/$(LIB_NAME).dll ../resources/native/x86_64

$(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).so: $(BUILD_DIR)/x86_64/linux/$(QUICKLIST_SOURCE).o
	mkdir -p $(TARGET_DIR)/x86_64; \
	$(CC) -shared $< $(CCFLAGS) -Wl,-headerpad_max_install_names -o $@; \
	mkdir -p ../resources/native/x86_64; \
	cp $(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).so ../resources/native/x86_64

$(TARGET_DIR)/x86_64/freebsd/$(POSIX_LIB_NAME).so: $(BUILD_DIR)/x86_64/freebsd/$(QUICKLIST_SOURCE).o
	mkdir -p $(TARGET_DIR)/x86_64/freebsd; \
	$(CC) -shared $< $(CCFLAGS) -Wl,-headerpad_max_install_names -o $@; \
	mkdir -p ../resources/native/x86_64/freebsd; \
	cp $(TARGET_DIR)/x86_64/freebsd/$(POSIX_LIB_NAME).so ../resources/native/x86_64/freebsd

clean:
	rm -rf $(TARGET_DIR)/build $(TARGET_DIR)/$(NATIVE)
