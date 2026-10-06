package mcopt.metal;

/** Minecraft's render enums to Metal enum values (see the Metal headers for the numbers). */
final class MetalConst {
	/** Buffer argument table layout shared by every translated shader. */
	static final int PUSH_CONSTANTS_INDEX = 26;
	static final int VERTEX_BUFFER_BASE = 27;

	private MetalConst() {
	}

	static int pixelFormat(Enum<?> format) {
		return switch (format.name()) {
			case "R8_UNORM" -> 10;
			case "R8_SNORM" -> 12;
			case "R8_UINT" -> 13;
			case "R8_SINT" -> 14;
			case "R16_UNORM" -> 20;
			case "R16_SNORM" -> 22;
			case "R16_UINT" -> 23;
			case "R16_SINT" -> 24;
			case "R16_FLOAT" -> 25;
			case "RG8_UNORM" -> 30;
			case "RG8_SNORM" -> 32;
			case "RG8_UINT" -> 33;
			case "RG8_SINT" -> 34;
			case "R32_UINT" -> 53;
			case "R32_SINT" -> 54;
			case "R32_FLOAT" -> 55;
			case "RG16_UNORM" -> 60;
			case "RG16_SNORM" -> 62;
			case "RG16_UINT" -> 63;
			case "RG16_SINT" -> 64;
			case "RG16_FLOAT" -> 65;
			case "RGBA8_UNORM" -> 70;
			case "RGBA8_SNORM" -> 72;
			case "RGBA8_UINT" -> 73;
			case "RGBA8_SINT" -> 74;
			case "RGB10A2_UNORM" -> 90;
			case "RGB10A2_UINT" -> 91;
			case "RG11B10_FLOAT" -> 92;
			case "RG32_UINT" -> 103;
			case "RG32_SINT" -> 104;
			case "RG32_FLOAT" -> 105;
			case "RGBA16_UNORM" -> 110;
			case "RGBA16_SNORM" -> 112;
			case "RGBA16_UINT" -> 113;
			case "RGBA16_SINT" -> 114;
			case "RGBA16_FLOAT" -> 115;
			case "RGBA32_UINT" -> 123;
			case "RGBA32_SINT" -> 124;
			case "RGBA32_FLOAT" -> 125;
			case "D16_UNORM" -> 250;
			case "D32_FLOAT" -> 252;
			case "S8_UINT" -> 253;
			case "D32_FLOAT_S8_UINT", "D24_UNORM_S8_UINT" -> 260; // Apple GPUs have no 24-bit depth
			default -> throw new IllegalArgumentException("Metal has no texture format for " + format);
		};
	}

	static int vertexFormat(Enum<?> format) {
		return switch (format.name()) {
			case "R8_UNORM" -> 47;
			case "R8_SNORM" -> 48;
			case "R8_UINT" -> 45;
			case "R8_SINT" -> 46;
			case "RG8_UNORM" -> 7;
			case "RG8_SNORM" -> 10;
			case "RG8_UINT" -> 1;
			case "RG8_SINT" -> 4;
			case "RGB8_UNORM" -> 8;
			case "RGB8_SNORM" -> 11;
			case "RGB8_UINT" -> 2;
			case "RGB8_SINT" -> 5;
			case "RGBA8_UNORM" -> 9;
			case "RGBA8_SNORM" -> 12;
			case "RGBA8_UINT" -> 3;
			case "RGBA8_SINT" -> 6;
			case "R16_UNORM" -> 51;
			case "R16_SNORM" -> 52;
			case "R16_UINT" -> 49;
			case "R16_SINT" -> 50;
			case "R16_FLOAT" -> 53;
			case "RG16_UNORM" -> 19;
			case "RG16_SNORM" -> 22;
			case "RG16_UINT" -> 13;
			case "RG16_SINT" -> 16;
			case "RG16_FLOAT" -> 25;
			case "RGB16_UNORM" -> 20;
			case "RGB16_SNORM" -> 23;
			case "RGB16_UINT" -> 14;
			case "RGB16_SINT" -> 17;
			case "RGB16_FLOAT" -> 26;
			case "RGBA16_UNORM" -> 21;
			case "RGBA16_SNORM" -> 24;
			case "RGBA16_UINT" -> 15;
			case "RGBA16_SINT" -> 18;
			case "RGBA16_FLOAT" -> 27;
			case "R32_FLOAT" -> 28;
			case "RG32_FLOAT" -> 29;
			case "RGB32_FLOAT" -> 30;
			case "RGBA32_FLOAT" -> 31;
			case "R32_SINT" -> 32;
			case "RG32_SINT" -> 33;
			case "RGB32_SINT" -> 34;
			case "RGBA32_SINT" -> 35;
			case "R32_UINT" -> 36;
			case "RG32_UINT" -> 37;
			case "RGB32_UINT" -> 38;
			case "RGBA32_UINT" -> 39;
			case "RGB10A2_UNORM" -> 41;
			case "RG11B10_FLOAT" -> 54;
			default -> throw new IllegalArgumentException("Metal has no vertex format for " + format);
		};
	}

	static int compare(Enum<?> op) {
		return switch (op.name()) {
			case "NEVER_PASS" -> 0;
			case "LESS_THAN" -> 1;
			case "EQUAL" -> 2;
			case "LESS_THAN_OR_EQUAL" -> 3;
			case "GREATER_THAN" -> 4;
			case "NOT_EQUAL" -> 5;
			case "GREATER_THAN_OR_EQUAL" -> 6;
			case "ALWAYS_PASS" -> 7;
			default -> throw new IllegalArgumentException("Metal has no compare op for " + op);
		};
	}

	static int blendFactor(Enum<?> factor) {
		return switch (factor.name()) {
			case "ZERO" -> 0;
			case "ONE" -> 1;
			case "SRC_COLOR" -> 2;
			case "ONE_MINUS_SRC_COLOR" -> 3;
			case "SRC_ALPHA" -> 4;
			case "ONE_MINUS_SRC_ALPHA" -> 5;
			case "DST_COLOR" -> 6;
			case "ONE_MINUS_DST_COLOR" -> 7;
			case "DST_ALPHA" -> 8;
			case "ONE_MINUS_DST_ALPHA" -> 9;
			case "SRC_ALPHA_SATURATE" -> 10;
			case "CONSTANT_COLOR" -> 11;
			case "ONE_MINUS_CONSTANT_COLOR" -> 12;
			case "CONSTANT_ALPHA" -> 13;
			case "ONE_MINUS_CONSTANT_ALPHA" -> 14;
			default -> throw new IllegalArgumentException("Metal has no blend factor for " + factor);
		};
	}

	static int blendOp(Enum<?> op) {
		return switch (op.name()) {
			case "ADD" -> 0;
			case "SUBTRACT" -> 1;
			case "REVERSE_SUBTRACT" -> 2;
			case "MIN" -> 3;
			case "MAX" -> 4;
			default -> throw new IllegalArgumentException("Metal has no blend op for " + op);
		};
	}

	/** MTLPrimitiveType. Quads and thick lines arrive pre-expanded into indexed triangles; 5 marks a fan, which mcmetal.m re-indexes into triangles. */
	static int primitive(Enum<?> topology) {
		return switch (topology.name()) {
			case "POINTS" -> 0;
			case "DEBUG_LINES" -> 1;
			case "DEBUG_LINE_STRIP" -> 2;
			case "LINES", "TRIANGLES", "QUADS" -> 3;
			case "TRIANGLE_STRIP" -> 4;
			case "TRIANGLE_FAN" -> 5;
			default -> throw new IllegalArgumentException("Metal has no primitive topology for " + topology);
		};
	}

	/** MTLPrimitiveTopologyClass: point 1, line 2, triangle 3. */
	static int topologyClass(Enum<?> topology) {
		return switch (primitive(topology)) {
			case 0 -> 1;
			case 1, 2 -> 2;
			default -> 3;
		};
	}

	static int addressMode(Enum<?> mode) {
		return switch (mode.name()) {
			case "CLAMP_TO_EDGE" -> 0;
			case "REPEAT" -> 2;
			default -> throw new IllegalArgumentException("Metal has no address mode for " + mode);
		};
	}

	static int filter(Enum<?> mode) {
		return mode.name().equals("LINEAR") ? 1 : 0;
	}
}
