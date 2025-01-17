/*
 * Copyright (c) 2015, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package org.graalvm.compiler.core.aarch64;

import jdk.vm.ci.aarch64.AArch64Kind;
import jdk.vm.ci.code.CodeUtil;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;
import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Equivalence;
import org.graalvm.compiler.asm.aarch64.AArch64Assembler.ExtendType;
import org.graalvm.compiler.asm.aarch64.AArch64MacroAssembler;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.calc.CanonicalCondition;
import org.graalvm.compiler.core.common.calc.FloatConvert;
import org.graalvm.compiler.core.gen.NodeMatchRules;
import org.graalvm.compiler.core.match.ComplexMatchResult;
import org.graalvm.compiler.core.match.MatchRule;
import org.graalvm.compiler.core.match.MatchableNode;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.lir.LIRFrameState;
import org.graalvm.compiler.lir.LabelRef;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.aarch64.AArch64ArithmeticOp;
import org.graalvm.compiler.lir.aarch64.AArch64BitFieldOp;
import org.graalvm.compiler.lir.aarch64.AArch64ControlFlow;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.DeoptimizingNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.AndNode;
import org.graalvm.compiler.nodes.calc.BinaryNode;
import org.graalvm.compiler.nodes.calc.FloatConvertNode;
import org.graalvm.compiler.nodes.calc.IntegerLessThanNode;
import org.graalvm.compiler.nodes.calc.LeftShiftNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.calc.NarrowNode;
import org.graalvm.compiler.nodes.calc.NegateNode;
import org.graalvm.compiler.nodes.calc.NotNode;
import org.graalvm.compiler.nodes.calc.OrNode;
import org.graalvm.compiler.nodes.calc.RightShiftNode;
import org.graalvm.compiler.nodes.calc.SubNode;
import org.graalvm.compiler.nodes.calc.UnaryNode;
import org.graalvm.compiler.nodes.calc.UnsignedRightShiftNode;
import org.graalvm.compiler.nodes.calc.XorNode;
import org.graalvm.compiler.nodes.calc.ZeroExtendNode;
import org.graalvm.compiler.nodes.memory.Access;

@MatchableNode(nodeClass = AArch64PointerAddNode.class, inputs = {"base", "offset"})
public class AArch64NodeMatchRules extends NodeMatchRules {
    private static final EconomicMap<Class<? extends BinaryNode>, AArch64ArithmeticOp> binaryOpMap;
    private static final EconomicMap<Class<? extends BinaryNode>, AArch64BitFieldOp.BitFieldOpCode> bitFieldOpMap;
    private static final EconomicMap<Class<? extends BinaryNode>, AArch64MacroAssembler.ShiftType> shiftTypeMap;

    static {
        binaryOpMap = EconomicMap.create(Equivalence.IDENTITY, 9);
        binaryOpMap.put(AddNode.class, AArch64ArithmeticOp.ADD);
        binaryOpMap.put(SubNode.class, AArch64ArithmeticOp.SUB);
        binaryOpMap.put(MulNode.class, AArch64ArithmeticOp.MUL);
        binaryOpMap.put(AndNode.class, AArch64ArithmeticOp.AND);
        binaryOpMap.put(OrNode.class, AArch64ArithmeticOp.OR);
        binaryOpMap.put(XorNode.class, AArch64ArithmeticOp.XOR);
        binaryOpMap.put(LeftShiftNode.class, AArch64ArithmeticOp.SHL);
        binaryOpMap.put(RightShiftNode.class, AArch64ArithmeticOp.ASHR);
        binaryOpMap.put(UnsignedRightShiftNode.class, AArch64ArithmeticOp.LSHR);

        bitFieldOpMap = EconomicMap.create(Equivalence.IDENTITY, 2);
        bitFieldOpMap.put(UnsignedRightShiftNode.class, AArch64BitFieldOp.BitFieldOpCode.UBFX);
        bitFieldOpMap.put(LeftShiftNode.class, AArch64BitFieldOp.BitFieldOpCode.UBFIZ);

        shiftTypeMap = EconomicMap.create(Equivalence.IDENTITY, 3);
        shiftTypeMap.put(LeftShiftNode.class, AArch64MacroAssembler.ShiftType.LSL);
        shiftTypeMap.put(RightShiftNode.class, AArch64MacroAssembler.ShiftType.ASR);
        shiftTypeMap.put(UnsignedRightShiftNode.class, AArch64MacroAssembler.ShiftType.LSR);
    }

    public AArch64NodeMatchRules(LIRGeneratorTool gen) {
        super(gen);
    }

    protected LIRFrameState getState(Access access) {
        if (access instanceof DeoptimizingNode) {
            return state((DeoptimizingNode) access);
        }
        return null;
    }

    protected AArch64Kind getMemoryKind(Access access) {
        return (AArch64Kind) gen.getLIRKind(access.asNode().stamp(NodeView.DEFAULT)).getPlatformKind();
    }

    private static ExtendType getZeroExtendType(int fromBits) {
        switch (fromBits) {
            case Byte.SIZE:
                return ExtendType.UXTB;
            case Short.SIZE:
                return ExtendType.UXTH;
            case Integer.SIZE:
                return ExtendType.UXTW;
            case Long.SIZE:
                return ExtendType.UXTX;
            default:
                GraalError.shouldNotReachHere("extended from " + fromBits + "bits is not supported!");
                return null;
        }
    }

    private AllocatableValue moveSp(AllocatableValue value) {
        return getLIRGeneratorTool().moveSp(value);
    }

    private ComplexMatchResult emitBitField(AArch64BitFieldOp.BitFieldOpCode op, ValueNode value, int lsb, int width) {
        assert op != null;
        assert value.getStackKind().isNumericInteger();

        return builder -> {
            Value a = operand(value);
            Variable result = gen.newVariable(LIRKind.combine(a));
            AllocatableValue src = moveSp(gen.asAllocatable(a));
            gen.append(new AArch64BitFieldOp(op, result, src, lsb, width));
            return result;
        };
    }

    private ComplexMatchResult emitBinaryShift(AArch64ArithmeticOp op, ValueNode value, BinaryNode shift,
                    boolean isShiftNot) {
        AArch64MacroAssembler.ShiftType shiftType = shiftTypeMap.get(shift.getClass());
        assert shiftType != null;
        assert value.getStackKind().isNumericInteger();
        assert shift.getX().getStackKind().isNumericInteger();
        assert shift.getY() instanceof ConstantNode;

        return builder -> {
            Value a = operand(value);
            Value b = operand(shift.getX());
            Variable result = gen.newVariable(LIRKind.combine(a, b));
            AllocatableValue x = moveSp(gen.asAllocatable(a));
            AllocatableValue y = moveSp(gen.asAllocatable(b));
            int shiftAmount = shift.getY().asJavaConstant().asInt();
            gen.append(new AArch64ArithmeticOp.BinaryShiftOp(op, result, x, y, shiftType, shiftAmount, isShiftNot));
            return result;
        };
    }

    private ComplexMatchResult emitBitTestAndBranch(FixedNode trueSuccessor, FixedNode falseSuccessor,
                    ValueNode value, double trueProbability, int nbits) {
        return builder -> {
            LabelRef trueDestination = getLIRBlock(trueSuccessor);
            LabelRef falseDestination = getLIRBlock(falseSuccessor);
            AllocatableValue src = moveSp(gen.asAllocatable(operand(value)));
            gen.append(new AArch64ControlFlow.BitTestAndBranchOp(trueDestination, falseDestination, src,
                            trueProbability, nbits));
            return null;
        };
    }

    private static boolean isNarrowingLongToInt(NarrowNode narrow) {
        return narrow.getInputBits() == 64 && narrow.getResultBits() == 32;
    }

    @MatchRule("(AArch64PointerAdd=addP base ZeroExtend)")
    @MatchRule("(AArch64PointerAdd=addP base (LeftShift ZeroExtend Constant))")
    public ComplexMatchResult extendedPointerAddShift(AArch64PointerAddNode addP) {
        ValueNode offset = addP.getOffset();
        ZeroExtendNode zeroExtend;
        int shiftNum;
        if (offset instanceof ZeroExtendNode) {
            zeroExtend = (ZeroExtendNode) offset;
            shiftNum = 0;
        } else {
            LeftShiftNode shift = (LeftShiftNode) offset;
            zeroExtend = (ZeroExtendNode) shift.getX();
            shiftNum = shift.getY().asJavaConstant().asInt();
        }

        int fromBits = zeroExtend.getInputBits();
        int toBits = zeroExtend.getResultBits();
        if (toBits != 64) {
            return null;
        }
        assert fromBits <= toBits;
        ExtendType extendType = getZeroExtendType(fromBits);

        if (shiftNum >= 0 && shiftNum <= 4) {
            ValueNode base = addP.getBase();
            return builder -> {
                AllocatableValue x = gen.asAllocatable(operand(base));
                AllocatableValue y = gen.asAllocatable(operand(zeroExtend.getValue()));
                AllocatableValue baseReference = LIRKind.derivedBaseFromValue(x);
                LIRKind kind = LIRKind.combineDerived(gen.getLIRKind(addP.stamp(NodeView.DEFAULT)),
                                baseReference, null);
                Variable result = gen.newVariable(kind);
                gen.append(new AArch64ArithmeticOp.ExtendedAddShiftOp(result, x, moveSp(y),
                                extendType, shiftNum));
                return result;
            };
        }
        return null;
    }

    @MatchRule("(And (UnsignedRightShift=shift a Constant=b) Constant=c)")
    @MatchRule("(LeftShift=shift (And a Constant=c) Constant=b)")
    public ComplexMatchResult unsignedBitField(BinaryNode shift, ValueNode a, ConstantNode b, ConstantNode c) {
        JavaKind srcKind = a.getStackKind();
        assert srcKind.isNumericInteger();
        AArch64BitFieldOp.BitFieldOpCode op = bitFieldOpMap.get(shift.getClass());
        assert op != null;
        int distance = b.asJavaConstant().asInt();
        long mask = c.asJavaConstant().asLong();

        // The Java(R) Language Specification CHAPTER 15.19 Shift Operators says:
        // "If the promoted type of the left-hand operand is int(long), then only the five(six)
        // lowest-order bits of the right-hand operand are used as the shift distance."
        distance = distance & (srcKind == JavaKind.Int ? 0x1f : 0x3f);

        // Constraint 1: Mask plus one should be a power-of-2 integer.
        if (!CodeUtil.isPowerOf2(mask + 1)) {
            return null;
        }
        int width = CodeUtil.log2(mask + 1);
        int srcBits = srcKind.getBitCount();
        // Constraint 2: Bit field width is less than 31(63) for int(long) as any bit field move
        // operations can be done by a single shift instruction if the width is 31(63).
        if (width >= srcBits - 1) {
            return null;
        }
        // Constraint 3: Sum of bit field width and the shift distance is less or equal to 32(64)
        // for int(long) as the specification of AArch64 bit field instructions.
        if (width + distance > srcBits) {
            return null;
        }
        return emitBitField(op, a, distance, width);
    }

    @MatchRule("(Add=binary a (LeftShift=shift b Constant))")
    @MatchRule("(Add=binary a (RightShift=shift b Constant))")
    @MatchRule("(Add=binary a (UnsignedRightShift=shift b Constant))")
    @MatchRule("(Sub=binary a (LeftShift=shift b Constant))")
    @MatchRule("(Sub=binary a (RightShift=shift b Constant))")
    @MatchRule("(Sub=binary a (UnsignedRightShift=shift b Constant))")
    public ComplexMatchResult addSubShift(BinaryNode binary, ValueNode a, BinaryNode shift) {
        AArch64ArithmeticOp op = binaryOpMap.get(binary.getClass());
        assert op != null;
        return emitBinaryShift(op, a, shift, false);
    }

    @MatchRule("(And=binary a (LeftShift=shift b Constant))")
    @MatchRule("(And=binary a (RightShift=shift b Constant))")
    @MatchRule("(And=binary a (UnsignedRightShift=shift b Constant))")
    @MatchRule("(Or=binary a (LeftShift=shift b Constant))")
    @MatchRule("(Or=binary a (RightShift=shift b Constant))")
    @MatchRule("(Or=binary a (UnsignedRightShift=shift b Constant))")
    @MatchRule("(Xor=binary a (LeftShift=shift b Constant))")
    @MatchRule("(Xor=binary a (RightShift=shift b Constant))")
    @MatchRule("(Xor=binary a (UnsignedRightShift=shift b Constant))")
    @MatchRule("(And=binary a (Not (LeftShift=shift b Constant)))")
    @MatchRule("(And=binary a (Not (RightShift=shift b Constant)))")
    @MatchRule("(And=binary a (Not (UnsignedRightShift=shift b Constant)))")
    @MatchRule("(Or=binary a (Not (LeftShift=shift b Constant)))")
    @MatchRule("(Or=binary a (Not (RightShift=shift b Constant)))")
    @MatchRule("(Or=binary a (Not (UnsignedRightShift=shift b Constant)))")
    @MatchRule("(Xor=binary a (Not (LeftShift=shift b Constant)))")
    @MatchRule("(Xor=binary a (Not (RightShift=shift b Constant)))")
    @MatchRule("(Xor=binary a (Not (UnsignedRightShift=shift b Constant)))")
    public ComplexMatchResult logicShift(BinaryNode binary, ValueNode a, BinaryNode shift) {
        AArch64ArithmeticOp op = binaryOpMap.get(binary.getClass());
        assert op != null;
        ValueNode operand = binary.getX() == a ? binary.getY() : binary.getX();
        boolean isShiftNot = operand instanceof NotNode;
        return emitBinaryShift(op, a, shift, isShiftNot);
    }

    @MatchRule("(Add=binary (Mul (SignExtend a) (SignExtend b)) c)")
    @MatchRule("(Sub=binary c (Mul (SignExtend a) (SignExtend b)))")
    public ComplexMatchResult signedMultiplyAddSubLong(BinaryNode binary, ValueNode a, ValueNode b, ValueNode c) {
        assert a.getStackKind() == JavaKind.Int && b.getStackKind() == JavaKind.Int && c.getStackKind() == JavaKind.Long;
        if (binary instanceof AddNode) {
            return builder -> getArithmeticLIRGenerator().emitIntegerMAdd(operand(a), operand(b), operand(c), true);
        }
        return builder -> getArithmeticLIRGenerator().emitIntegerMSub(operand(a), operand(b), operand(c), true);
    }

    @MatchRule("(Negate (Mul=mul (SignExtend a) (SignExtend b)))")
    @MatchRule("(Mul=mul (Negate (SignExtend a)) (SignExtend b))")
    public ComplexMatchResult signedMultiplyNegLong(MulNode mul, ValueNode a, ValueNode b) {
        assert a.getStackKind() == JavaKind.Int && b.getStackKind() == JavaKind.Int;
        LIRKind resultKind = LIRKind.fromJavaKind(gen.target().arch, mul.getStackKind());
        return builder -> getArithmeticLIRGenerator().emitBinary(
                        resultKind, AArch64ArithmeticOp.SMNEGL, true, operand(a), operand(b));
    }

    @MatchRule("(Mul=mul (SignExtend a) (SignExtend b))")
    public ComplexMatchResult signedMultiplyLong(MulNode mul, ValueNode a, ValueNode b) {
        assert a.getStackKind() == JavaKind.Int && b.getStackKind() == JavaKind.Int;
        LIRKind resultKind = LIRKind.fromJavaKind(gen.target().arch, mul.getStackKind());
        return builder -> getArithmeticLIRGenerator().emitBinary(
                        resultKind, AArch64ArithmeticOp.SMULL, true, operand(a), operand(b));
    }

    @MatchRule("(Add=binary (Narrow=narrow a) (Narrow b))")
    @MatchRule("(Sub=binary (Narrow=narrow a) (Narrow b))")
    @MatchRule("(Mul=binary (Narrow=narrow a) (Narrow b))")
    @MatchRule("(And=binary (Narrow=narrow a) (Narrow b))")
    @MatchRule("(Or=binary (Narrow=narrow a) (Narrow b))")
    @MatchRule("(Xor=binary (Narrow=narrow a) (Narrow b))")
    @MatchRule("(LeftShift=binary (Narrow=narrow a) (Narrow b))")
    @MatchRule("(RightShift=binary (Narrow=narrow a) (Narrow b))")
    @MatchRule("(UnsignedRightShift=binary (Narrow=narrow a) (Narrow b))")
    @MatchRule("(Add=binary a (Narrow=narrow b))")
    @MatchRule("(Sub=binary a (Narrow=narrow b))")
    @MatchRule("(Mul=binary a (Narrow=narrow b))")
    @MatchRule("(And=binary a (Narrow=narrow b))")
    @MatchRule("(Or=binary a (Narrow=narrow b))")
    @MatchRule("(Xor=binary a (Narrow=narrow b))")
    @MatchRule("(LeftShift=binary a (Narrow=narrow b))")
    @MatchRule("(RightShift=binary a (Narrow=narrow b))")
    @MatchRule("(UnsignedRightShift=binary a (Narrow=narrow b))")
    @MatchRule("(Sub=binary (Narrow=narrow a) b)")
    @MatchRule("(LeftShift=binary (Narrow=narrow a) b)")
    @MatchRule("(RightShift=binary (Narrow=narrow a) b)")
    @MatchRule("(UnsignedRightShift=binary (Narrow=narrow a) b)")
    public ComplexMatchResult elideL2IForBinary(BinaryNode binary, NarrowNode narrow) {
        assert binary.getStackKind().isNumericInteger();

        ValueNode a = narrow;
        ValueNode b = binary.getX() == narrow ? binary.getY() : binary.getX();
        boolean isL2Ia = isNarrowingLongToInt((NarrowNode) a);
        boolean isL2Ib = (b instanceof NarrowNode) && isNarrowingLongToInt((NarrowNode) b);
        if (!isL2Ia && !isL2Ib) {
            return null;
        }
        // Get the value of L2I NarrowNode as the src value.
        ValueNode src1 = isL2Ia ? ((NarrowNode) a).getValue() : a;
        ValueNode src2 = isL2Ib ? ((NarrowNode) b).getValue() : b;

        AArch64ArithmeticOp op = binaryOpMap.get(binary.getClass());
        assert op != null;
        boolean commutative = binary.getNodeClass().isCommutative();
        LIRKind resultKind = LIRKind.fromJavaKind(gen.target().arch, binary.getStackKind());

        // Must keep the right operator order for un-commutative binary operations.
        if (a == binary.getX()) {
            return builder -> getArithmeticLIRGenerator().emitBinary(
                            resultKind, op, commutative, operand(src1), operand(src2));
        }
        return builder -> getArithmeticLIRGenerator().emitBinary(
                        resultKind, op, commutative, operand(src2), operand(src1));
    }

    @MatchRule("(Negate=unary (Narrow=narrow value))")
    @MatchRule("(Not=unary (Narrow=narrow value))")
    public ComplexMatchResult elideL2IForUnary(UnaryNode unary, NarrowNode narrow) {
        assert unary.getStackKind().isNumericInteger();
        if (!isNarrowingLongToInt(narrow)) {
            return null;
        }

        AArch64ArithmeticOp op = unary instanceof NegateNode ? AArch64ArithmeticOp.NEG
                        : AArch64ArithmeticOp.NOT;
        return builder -> {
            AllocatableValue input = gen.asAllocatable(operand(narrow.getValue()));
            LIRKind resultKind = LIRKind.fromJavaKind(gen.target().arch, unary.getStackKind());
            Variable result = gen.newVariable(resultKind);
            gen.append(new AArch64ArithmeticOp.UnaryOp(op, result, moveSp(input)));
            return result;
        };
    }

    @MatchRule("(Mul (Negate a) b)")
    @MatchRule("(Negate (Mul a b))")
    public ComplexMatchResult multiplyNegate(ValueNode a, ValueNode b) {
        if (a.getStackKind().isNumericInteger() && b.getStackKind().isNumericInteger()) {
            return builder -> getArithmeticLIRGenerator().emitMNeg(operand(a), operand(b));
        }
        return null;
    }

    @MatchRule("(Add=binary (Mul a b) c)")
    @MatchRule("(Sub=binary c (Mul a b))")
    public ComplexMatchResult multiplyAddSub(BinaryNode binary, ValueNode a, ValueNode b, ValueNode c) {
        JavaKind kindA = a.getStackKind();
        JavaKind kindB = b.getStackKind();
        JavaKind kindC = c.getStackKind();
        if (!kindA.isNumericInteger() || !kindB.isNumericInteger() || !kindC.isNumericInteger()) {
            return null;
        }

        if (binary instanceof AddNode) {
            return builder -> getArithmeticLIRGenerator().emitIntegerMAdd(operand(a), operand(b), operand(c), false);
        }
        return builder -> getArithmeticLIRGenerator().emitIntegerMSub(operand(a), operand(b), operand(c), false);
    }

    /**
     * ((x & (1 << n)) == 0) -> tbz/tbnz n label.
     */
    @MatchRule("(If (IntegerTest value Constant=a))")
    public ComplexMatchResult testBitAndBranch(IfNode root, ValueNode value, ConstantNode a) {
        if (value.getStackKind().isNumericInteger()) {
            long constant = a.asJavaConstant().asLong();
            if (Long.bitCount(constant) == 1) {
                return emitBitTestAndBranch(root.trueSuccessor(), root.falseSuccessor(), value,
                                root.getTrueSuccessorProbability(), Long.numberOfTrailingZeros(constant));
            }
        }
        return null;
    }

    /**
     * if x < 0 <=> tbz x, sizeOfBits(x) - 1, label.
     */
    @MatchRule("(If (IntegerLessThan=lessNode x Constant=y))")
    public ComplexMatchResult checkNegativeAndBranch(IfNode root, IntegerLessThanNode lessNode, ValueNode x, ConstantNode y) {
        JavaKind xKind = x.getStackKind();
        assert xKind.isNumericInteger();
        if (y.isJavaConstant() && (0 == y.asJavaConstant().asLong()) && lessNode.condition().equals(CanonicalCondition.LT)) {
            return emitBitTestAndBranch(root.falseSuccessor(), root.trueSuccessor(), x,
                            1.0 - root.getTrueSuccessorProbability(), xKind.getBitCount() - 1);
        }
        return null;
    }

    @MatchRule("(FloatConvert=a (Sqrt (FloatConvert=b c)))")
    public ComplexMatchResult floatSqrt(FloatConvertNode a, FloatConvertNode b, ValueNode c) {
        if (c.getStackKind().isNumericFloat() && a.getStackKind().isNumericFloat()) {
            if (a.getFloatConvert() == FloatConvert.D2F && b.getFloatConvert() == FloatConvert.F2D) {
                return builder -> getArithmeticLIRGenerator().emitMathSqrt(operand(c));
            }
        }
        return null;
    }

    @Override
    public AArch64LIRGenerator getLIRGeneratorTool() {
        return (AArch64LIRGenerator) gen;
    }

    protected AArch64ArithmeticLIRGenerator getArithmeticLIRGenerator() {
        return (AArch64ArithmeticLIRGenerator) getLIRGeneratorTool().getArithmetic();
    }
}
