.PHONY: all build test clean run/hello setup

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

## Run the hello example
run/hello:
	lein hello

## Compile shaders for the hello example
shaders/hello:
	glslc examples/hello/shaders/triangle.vert -o examples/hello/shaders/triangle.vert.spv
	glslc examples/hello/shaders/triangle.frag -o examples/hello/shaders/triangle.frag.spv

## Build + compile shaders for hello
hello: shaders/hello run/hello
