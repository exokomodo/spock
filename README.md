# Spock

A Vulkan game engine written in Clojure, using [LWJGL](https://www.lwjgl.org/) for native bindings.

Spiritual Clojure counterpart to [drakon](https://github.com/exokomodo/drakon).

## Requirements

- JDK 17+
- [Leiningen](https://leiningen.org/)
- Vulkan-capable GPU + driver (`libvulkan.so`)
- `glslc` (from `glslang-tools`) for shader compilation

## Usage

```bash
# Install dependencies
make setup

# Run the hello example
make run/hello
```

## Structure

```
src/spock/
  renderer/     — Renderer protocol + Vulkan backend
  renderable/   — Renderable protocol
  shader/       — GLSL compilation + SPIR-V loading
  game/         — Game loop abstraction
examples/
  hello/        — Triangle + animated clear color
```

## Examples

### Hello Vulkan

Renders a triangle with an animated clear color that cycles through RGB.

```bash
make hello
```
