SHELL := /bin/bash
.SHELLFLAGS = -e -c
.DEFAULT_GOAL := help
.ONESHELL:
.SILENT:
MAKEFLAGS += --no-print-directory

ifneq (,$(wildcard ./.env))
    include .env
    export
endif

.PHONY: all build test clean run/hello setup

# Detect headless environment: no Wayland or X11 display available.
# If headless, prefix commands that open windows with cage (Wayland kiosk).
# cage is only used when the binary is available; otherwise fail loudly.
OS := $(shell uname -s)
ifeq ($(WAYLAND_DISPLAY)$(DISPLAY),)
  ifeq ($(OS),Linux)
    CAGE_AVAILABLE := $(shell which cage 2>/dev/null)
    ifdef CAGE_AVAILABLE
      DISPLAY_PREFIX := cage --
    else
      $(warning Neither WAYLAND_DISPLAY nor DISPLAY is set, and cage is not installed.)
      $(warning Install cage or run from a Wayland/X11 session.)
      DISPLAY_PREFIX :=
    endif
  else
    DISPLAY_PREFIX :=
  endif
else
  DISPLAY_PREFIX :=
endif

BIN_DIR ?= /usr/local/bin

# macOS: LWJGL loads libvulkan.1.dylib directly via DYLD_LIBRARY_PATH.
# The LunarG SDK installs it to /usr/local/lib by default.
# Override VULKAN_LIB_DIR if your SDK is elsewhere.
ifeq ($(OS),Darwin)
  VULKAN_LIB_DIR ?= /usr/local/lib
  export DYLD_LIBRARY_PATH := $(VULKAN_LIB_DIR):$(DYLD_LIBRARY_PATH)
endif

##@ Setup environment

.PHONY: setup
setup: setup/lein ## Setup the development environment

.PHONY: setup/lein
setup/lein:
	curl -fL https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein > $(BIN_DIR)/lein
	chmod +x $(BIN_DIR)/lein

##@ Development tools
.PHONY: build
build: ## Build the project
	lein compile

.PHONY: clean
clean: ## Clean the project
	lein clean

.PHONY: deps
deps: ## Install project dependencies
	lein deps

.PHONY: test
test: ## Run tests
	lein test

##@ Examples
.PHONY: run/hello
run/hello: shaders/hello
	$(DISPLAY_PREFIX) lein hello

.PHONY: shaders/hello
shaders/hello:
	glslc examples/hello/shaders/triangle.vert -o examples/hello/shaders/triangle.vert.spv
	glslc examples/hello/shaders/triangle.frag -o examples/hello/shaders/triangle.frag.spv

.PHONY: hello
hello: run/hello ## Run the hello example

##@ Utilities

.PHONY: help
help: ## Displays help info
	awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make \033[36m\033[0m\n"} /^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2 } /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } ' $(MAKEFILE_LIST)
