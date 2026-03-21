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
      DISPLAY_PREFIX :=
    endif
  else
    DISPLAY_PREFIX :=
  endif
else
  DISPLAY_PREFIX :=
endif

BIN_DIR ?= /usr/local/bin

# macOS: point LWJGL at the Vulkan loader by full path using
# -Dorg.lwjgl.vulkan.libname so we don't redirect all LWJGL native
# library loading (which causes version conflicts with Homebrew's dylibs).
# LunarG SDK installs the loader to /usr/local/lib/libvulkan.1.dylib.
# Override VULKAN_LOADER if yours is elsewhere.
ifeq ($(OS),Darwin)
  VULKAN_LOADER ?= /usr/local/lib/libvulkan.1.dylib
  export JVM_OPTS := -Dorg.lwjgl.vulkan.libname=$(VULKAN_LOADER) $(JVM_OPTS)
endif

_GLSLC = $(shell which glslc)
ifeq ($(_GLSLC),)
  $(warning "glslc shader compiler not found in PATH. Please install it (e.g. via 'make setup/glslc') and ensure it's available in your PATH.")
endif
GLSLC = $(_GLSLC) -Werror
LEIN = $(shell which lein)
ifeq ($(LEIN),)
  $(warning "Leiningen not found in PATH. Please install it (e.g. via 'make setup/lein') and ensure it's available in your PATH.")
endif

##@ Setup environment

.PHONY: setup
setup: setup/glslc setup/lein setup/hooks ## Setup the development environment

.PHONY: setup/glslc
setup/glslc: ## Install glslc shader compiler (macOS: brew; Linux: apt)
ifeq ($(OS),Darwin)
	brew install glslang
else
	sudo apt-get update -qq && sudo apt-get install -y --no-install-recommends glslc
endif

.PHONY: setup/hooks
setup/hooks: ## Install git hooks
	ln -sf "$(PWD)/git/hooks/pre-commit" .git/hooks/pre-commit
	echo "✅ Git hooks installed"

.PHONY: setup/lein
setup/lein:
	curl -fL https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein > $(BIN_DIR)/lein
	chmod +x $(BIN_DIR)/lein

##@ Development tools
.PHONY: build
build: build/engine ## Build the engine

.PHONY: build/all
build/all: build/engine ## Compile everything (including examples)
	$(LEIN) with-profile hello compile
	$(LEIN) with-profile spin-shooter compile

.PHONY: build/engine
build/engine: build/shaders/engine ## Build the engine alone
	$(LEIN) compile

.PHONY: build/shaders
build/shaders: build/shaders/engine build/shaders/examples ## Build all shaders

.PHONY: build/shaders/engine
build/shaders/engine: build/shaders/engine/polygon build/shaders/engine/sprite ## Build engine shaders

.PHONY: build/shaders/engine/polygon
build/shaders/engine/polygon: ## Compile the polygon shaders (used by :polygon renderable)
	set -v
	$(GLSLC) src/shaders/polygon.vert -o src/shaders/polygon.vert.spv
	$(GLSLC) src/shaders/polygon.frag -o src/shaders/polygon.frag.spv

.PHONY: build/shaders/engine/sprite
build/shaders/engine/sprite: ## Compile the sprite shaders (used by :sprite renderable)
	set -v
	$(GLSLC) src/shaders/sprite.vert -o src/shaders/sprite.vert.spv
	$(GLSLC) src/shaders/sprite.frag -o src/shaders/sprite.frag.spv

.PHONY: build/shaders/examples
build/shaders/examples: build/shaders/examples/hello ## Build example shaders

.PHONY: build/shaders/examples/hello
build/shaders/examples/hello: ## Compile the hello example shaders
	set -v
	$(GLSLC) examples/hello/shaders/triangle.vert -o examples/hello/shaders/triangle.vert.spv
	$(GLSLC) examples/hello/shaders/triangle.frag -o examples/hello/shaders/triangle.frag.spv

.PHONY: check
check: check/format ## Check code quality

.PHONY: check/format
check/format: ## Check code formatting with cljfmt
	$(LEIN) cljfmt check

.PHONY: clean
clean: ## Clean the project
	$(LEIN) clean

.PHONY: deps
deps: ## Install project dependencies
	$(LEIN) deps

.PHONY: fix
fix: fix/format ## Fix code issues

.PHONY: fix/format
fix/format: ## Fix code formatting
	$(LEIN) cljfmt fix

# screenrecord — capture the cage window to a file.
# Requires cage and wf-recorder (wlroots-based screen capture for cage's Wayland compositor).
# Requires LIBSEAT_BACKEND=seatd (seatd must be running) for headless/SSH use.
# Usage: make screenrecord [OUTPUT=my-recording.mp4] [GEOMETRY=0,0 1920x1080]
OUTPUT   ?= recording.mp4
GEOMETRY ?= 0,0 1920x1080
RECORD_EXAMPLE ?= hello

.PHONY: screenrecord
screenrecord: shaders/hello ## Record the hello window via cage + wf-recorder (OUTPUT=recording.mp4)
	$(if $(shell which cage 2>/dev/null),,$(error cage is required for screenrecord but was not found))
	$(if $(shell which wf-recorder 2>/dev/null),,$(error wf-recorder is required for screenrecord but was not found))
	echo "Recording to $(OUTPUT)…"
	LIBSEAT_BACKEND=seatds
	WLR_NO_HARDWARE_CURSORS=1s
	WLR_DRM_DEVICES=/dev/dri/card1s
	cage -- sh -c '\
		wf-recorder --codec libx264 -g "$(GEOMETRY)" -f "$(OUTPUT)" & WF_PID=$$!; \
		sleep 2; \
		$(LEIN) $(RECORD_EXAMPLE); \
		kill -INT $$WF_PID; \
		wait $$WF_PID 2>/dev/null || true'

.PHONY: test
test: ## Run tests
	$(LEIN) test

##@ Examples

.PHONY: run/hello
run/hello: build/shaders ## Run the hello example
	$(DISPLAY_PREFIX) $(LEIN) hello

.PHONY: run/spin-shooter
run/spin-shooter: build/shaders ## Run the spin-shooter example
	$(DISPLAY_PREFIX) $(LEIN) spin-shooter

##@ Utilities

.PHONY: help
help: ## Displays help info
	awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make \033[36m\033[0m\n"} /^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2 } /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } ' $(MAKEFILE_LIST)
