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

##@ Setup environment

.PHONY: setup
setup: setup/lein ## Setup the development environment

.PHONY: setup/lein
setup/lein:
	curl -fL https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein > $(BIN_DIR)/lein
	chmod +x $(BIN_DIR)/lein

.PHONY: setup/glslc
setup/glslc: ## Install glslc shader compiler (macOS: brew; Linux: apt)
ifeq ($(OS),Darwin)
	brew install glslang
else
	sudo apt-get update -qq && sudo apt-get install -y --no-install-recommends glslc
endif

##@ Development tools
.PHONY: build
build: ## Build the project
	lein compile

.PHONY: build/shaders
build/shaders: shaders/polygon ## Build engine shaders

.PHONY: shaders/polygon
shaders/polygon: ## Compile the polygon shaders (used by :polygon renderable)
	glslc src/shaders/polygon.vert -o src/shaders/polygon.vert.spv
	glslc src/shaders/polygon.frag -o src/shaders/polygon.frag.spv

.PHONY: check
check: check/format check/compile ## Check code quality

.PHONY: check/compile
check/compile: ## Check code quality
	lein with-profile edn compile
	lein with-profile spin-shooter compile

.PHONY: check/format
check/format: ## Check code formatting with cljfmt
	lein cljfmt check

.PHONY: clean
clean: ## Clean the project
	lein clean

.PHONY: deps
deps: ## Install project dependencies
	lein deps

.PHONY: fix
fix: fix/format ## Fix code issues

.PHONY: fix/format
fix/format: ## Fix code formatting
	lein cljfmt fix

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
		lein $(RECORD_EXAMPLE); \
		kill -INT $$WF_PID; \
		wait $$WF_PID 2>/dev/null || true'

.PHONY: test
test: ## Run tests
	lein test

##@ Examples

EDN ?= examples/hello/game.edn
.PHONY: run/edn
run/edn: shaders/hello ## EDN-driven game runner. Usage: make run/edn EDN=examples/hello/game.edn
	$(DISPLAY_PREFIX) lein edn $(EDN)

.PHONY: run/hello
run/hello: shaders/hello ## Run the hello example
	$(DISPLAY_PREFIX) lein hello

.PHONY: shaders/hello
shaders/hello:
	glslc examples/hello/shaders/triangle.vert -o examples/hello/shaders/triangle.vert.spv
	glslc examples/hello/shaders/triangle.frag -o examples/hello/shaders/triangle.frag.spv

.PHONY: run/spin-shooter
run/spin-shooter: shaders/polygon ## Run the spin-shooter example
	$(DISPLAY_PREFIX) lein spin-shooter

.PHONY: edn
edn: run/edn ## Run a game from an EDN file (EDN=path/to/game.edn)

##@ Utilities

.PHONY: help
help: ## Displays help info
	awk 'BEGIN {FS = ":.*##"; printf "\nUsage:\n  make \033[36m\033[0m\n"} /^[a-zA-Z_-]+:.*?##/ { printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2 } /^##@/ { printf "\n\033[1m%s\033[0m\n", substr($$0, 5) } ' $(MAKEFILE_LIST)
