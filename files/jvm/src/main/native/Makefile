TARGET_DIR := ../../../target
BUILD_DIR := $(TARGET_DIR)/build
NATIVE_DIR := native/$(shell uname -sm | cut -d ' ' -f2 | tr '[:upper:]' '[:lower:]')
LIB_DIR := ../resources/$(NATIVE_DIR)
LIB_NAME := swoval-files0
POSIX_LIB_NAME := lib$(LIB_NAME)
QUICKLIST_SOURCE := com_swoval_files_NativeDirectoryLister
WIN32CC := i686-w64-mingw32-g++
WIN64CC := x86_64-w64-mingw32-g++
CC := clang

UNAME_S := $(shell uname -s)

ifeq ($(UNAME_S),Darwin)
	JNI_INCLUDE := -I/System/Library/Frameworks/JavaVM.framework/Versions/Current/Headers/
	LD := /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/c++
	OBJS := $(BUILD_DIR)/x86_64/darwin/$(QUICKLIST_SOURCE).o \
		$(BUILD_DIR)/x86_64/darwin/com_swoval_files_FileEventsApi.o \
		$(BUILD_DIR)/x86_64/windows/$(QUICKLIST_SOURCE).o \
		$(BUILD_DIR)/i686/windows/$(QUICKLIST_SOURCE).o \

	LIBS := $(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).dylib \
		$(TARGET_DIR)/x86_64/$(LIB_NAME).dll \
		$(TARGET_DIR)/i686/$(LIB_NAME).dll \


endif

ifeq ($(UNAME_S), Linux)
	BASE_INCLUDE := $(shell locate jni.h | tail -n 1 | xargs dirname)
	JNI_INCLUDE := -I$(BASE_INCLUDE) -I$(BASE_INCLUDE)/linux
	OBJS := $(BUILD_DIR)/x86_64/linux/$(QUICKLIST_SOURCE).o \
	OBJS := $(BUILD_DIR)/i686/linux/$(QUICKLIST_SOURCE).o \

	LIBS := $(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).so \
		$(TARGET_DIR)/i686/$(POSIX_LIB_NAME).so \

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

$(BUILD_DIR)/i686/linux/$(QUICKLIST_SOURCE).o: posix/$(QUICKLIST_SOURCE).cc
	echo $(JNI_INCLUDE)
	mkdir -p $(BUILD_DIR)/i686/linux; \
	$(CC) -c -m32 $< $(CCFLAGS) $(JNI_INCLUDE) -fPIC -o $@

$(BUILD_DIR)/x86_64/windows/$(QUICKLIST_SOURCE).o: windows/$(QUICKLIST_SOURCE).cc
	mkdir -p $(BUILD_DIR)/x86_64/windows; \
	$(WIN64CC) -c $< $(CCFLAGS) -o $@ -D__WIN__

$(BUILD_DIR)/i686/windows/$(QUICKLIST_SOURCE).o: windows/$(QUICKLIST_SOURCE).cc
	mkdir -p $(BUILD_DIR)/i686/windows; \
	$(WIN32CC) -c $< $(CCFLAGS) -o $@ -D__WIN__

$(BUILD_DIR)/x86_64/darwin/$(QUICKLIST_SOURCE).o: posix/$(QUICKLIST_SOURCE).cc
	mkdir -p $(BUILD_DIR)/x86_64/darwin; \
	$(CC) -c $< $(CCFLAGS) -framework Carbon -o $@

$(BUILD_DIR)/x86_64/darwin/com_swoval_files_apple_FileEventsApi.o: $(NPMDIR)/swoval_apple_file_system.hpp
$(BUILD_DIR)/x86_64/darwin/com_swoval_files_apple_FileEventsApi.o: apple/com_swoval_files_apple_FileEventsApi.cc
	mkdir -p $(BUILD_DIR)/x86_64/darwin; \
	$(CC) -c $< $(CCFLAGS) -framework Carbon -o $@

$(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).dylib: $(BUILD_DIR)/x86_64/darwin/com_swoval_files_apple_FileEventsApi.o \
	$(BUILD_DIR)/x86_64/darwin/$(QUICKLIST_SOURCE).o 
	mkdir -p $(TARGET_DIR)/x86_64; \
	$(LD) -dynamiclib -framework Carbon $(CCFLAGS) -Wl,-headerpad_max_install_names -install_name @rpath/$(POSIX_LIB_NAME) \
	$(BUILD_DIR)/x86_64/darwin/com_swoval_files_apple_FileEventsApi.o \
	$(BUILD_DIR)/x86_64/darwin/$(QUICKLIST_SOURCE).o  \
	 -o $@ ; \
	mkdir -p ../resources/native/x86_64; \
	cp $(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).dylib ../resources/native/x86_64



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

$(TARGET_DIR)/i686/$(LIB_NAME).dll: $(BUILD_DIR)/i686/windows/$(QUICKLIST_SOURCE).o
	mkdir -p $(TARGET_DIR)/i686; \
	$(WIN32CC) $(LINUX_LDFLAGS) $< $(CCFLAGS) -Wl,-headerpad_max_install_names -o $@ \
		-D__WIN__ \
		-Wall -Wextra \
		-nostdlib -ffreestanding -mconsole -Os \
		-fno-stack-check -fno-stack-protector -mno-stack-arg-probe \
		-fno-leading-underscore \
		-lkernel32; \
	mkdir -p ../resources/native/i686; \
	cp $(TARGET_DIR)/i686/$(LIB_NAME).dll ../resources/native/i686

$(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).so: $(BUILD_DIR)/x86_64/linux/$(QUICKLIST_SOURCE).o
	mkdir -p $(TARGET_DIR)/x86_64; \
	$(CC) -shared $< $(CCFLAGS) -Wl,-headerpad_max_install_names -o $@; \
	mkdir -p ../resources/native/x86_64; \
	cp $(TARGET_DIR)/x86_64/$(POSIX_LIB_NAME).so ../resources/native/x86_64

$(TARGET_DIR)/i686/$(POSIX_LIB_NAME).so: $(BUILD_DIR)/i686/linux/$(QUICKLIST_SOURCE).o
	mkdir -p $(TARGET_DIR)/i686; \
	$(CC) -m32 -shared $< $(CCFLAGS) -Wl,-headerpad_max_install_names -o $@; \
	mkdir -p ../resources/native/i686; \
	cp $(TARGET_DIR)/i686/$(POSIX_LIB_NAME).so ../resources/native/i686

clean:
	rm -rf $(TARGET_DIR)/build $(TARGET_DIR)/$(NATIVE)
