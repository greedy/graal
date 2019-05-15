/*
 * Copyright (c) 2019, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.wasm.binary;

import static com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import static com.oracle.truffle.wasm.binary.Assert.format;
import static com.oracle.truffle.wasm.binary.Instructions.DROP;
import static com.oracle.truffle.wasm.binary.Instructions.END;
import static com.oracle.truffle.wasm.binary.Instructions.I32_ADD;
import static com.oracle.truffle.wasm.binary.Instructions.I32_CONST;
import static com.oracle.truffle.wasm.binary.Instructions.I64_CONST;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

public class WasmBlockNode extends WasmNode {
    @CompilationFinal private final int startOffset;
    @CompilationFinal private final int size;
    @CompilationFinal private final byte typeId;

    public WasmBlockNode(WasmCodeEntry codeEntry, int startOffset, int size, byte typeId) {
        super(codeEntry);
        this.startOffset = startOffset;
        this.size = size;
        this.typeId = typeId;
    }

    @ExplodeLoop
    public void execute(VirtualFrame frame) {
        int offset = startOffset;
        while (offset < startOffset + size) {
            byte opcode = BinaryStreamReader.peek1(codeEntry().data(), offset);
            offset++;
            switch (opcode) {
                case END:
                    break;
                case DROP: {
                    pop(frame);
                    break;
                }
                case I32_CONST: {
                    int value = BinaryStreamReader.peek1(codeEntry().data(), offset);  // TODO: Fix
                    pushInt(frame, value);
                    offset++;
                    break;
                }
                case I64_CONST: {
                    Assert.fail("Not implemented");
                    break;
                }
                case I32_ADD: {
                    int x = popInt(frame);
                    int y = popInt(frame);
                    pushInt(frame, x + y);
                    break;
                }
                default:
                    Assert.fail(format("Unknown opcode: 0x%02X", opcode));
            }
        }
    }

    public byte typeId() {
        return typeId;
    }
}
