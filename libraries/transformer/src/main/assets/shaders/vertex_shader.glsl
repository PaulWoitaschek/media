// Copyright 2021 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
attribute vec4 a_position;
attribute vec4 a_texcoord;
uniform mat4 tex_transform;
uniform mat4 transformation_matrix;
varying vec2 v_texcoord;
void main() {
  gl_Position = transformation_matrix * a_position;
  v_texcoord = (tex_transform * a_texcoord).xy;
}
