CC := /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/c++
NPMDIR := ../../../../js/npm/src/
JNI_INCLUDE := /System/Library/Frameworks/JavaVM.framework/Versions/Current/Headers/
CCFLAGS := -I./include -I$(NPMDIR) -I$(JNI_INCLUDE) -framework Carbon -Wno-unused-command-line-argument -std=c++11 -O3
LIB_DIR := ../resources/native/x86_64-darwin
LIB_NAME := libapple-file-events0.dylib
LIB := $(LIB_DIR)/$(LIB_NAME)
NATIVE := native/x86_64-darwin
TARGET_DIR := ../../../target
BUILD_DIR := $(TARGET_DIR)/build
TARGET := $(TARGET_DIR)/$(NATIVE)/$(LIB_NAME)
SOURCES := $(wildcard *.cc)
OBJS := $(patsubst %.cc, $(BUILD_DIR)/%.o, $(SOURCES))
HEADER := ./include/com_swoval_files_apple_FileEventsApi.h

all: $(LIB)

.PHONY: clean all

$(LIB): $(TARGET)
	echo $(LIB)
	mkdir -p $(LIB_DIR); \
	cp $(TARGET) $(LIB)

$(OBJS): $(SOURCES) $(HEADER)
	mkdir -p $(BUILD_DIR); \
	$(CC) -c $< $(CCFLAGS) -o $@

$(TARGET): $(OBJS)
	mkdir -p $(TARGET_DIR)/$(NATIVE); \
	$(CC) -dynamiclib $< $(CCFLAGS) -Wl,-headerpad_max_install_names -install_name @rpath/$(LIB_NAME) -o $@

clean:
	rm -rf $(TARGET_DIR)/build $(TARGET_DIR)/$(NATIVE)
