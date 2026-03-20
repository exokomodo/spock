.PHONY: all build test clean run/hello setup

# Detect headless environment: no Wayland or X11 display available.
# If headless, prefix commands that open windows with cage (Wayland kiosk).
# cage is only used when the binary is available; otherwise fail loudly.
ifeq ($(WAYLAND_DISPLAY)$(DISPLAY),)
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

# Default target
all: build

## Setup — install lein dependencies
setup:
	lein deps

## Build — compile all namespaces
build:
	lein compile

## Test — run test suite
test:
	lein test

## Clean — remove compiled artifacts
clean:
	lein clean

## Run the hello example (uses cage if headless)
run/hello:
	$(DISPLAY_PREFIX) lein hello

## Compile shaders for the hello example
shaders/hello:
	glslc examples/hello/shaders/triangle.vert -o examples/hello/shaders/triangle.vert.spv
	glslc examples/hello/shaders/triangle.frag -o examples/hello/shaders/triangle.frag.spv

## Build + compile shaders + run hello
hello: shaders/hello run/hello
