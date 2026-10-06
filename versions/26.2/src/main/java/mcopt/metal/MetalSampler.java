package mcopt.metal;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import java.util.OptionalDouble;

final class MetalSampler extends GpuSampler {
	final long handle;
	private final AddressMode u, v;
	private final FilterMode min, mag;
	private final int anisotropy;
	private final OptionalDouble maxLod;

	private MetalSampler(long handle, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
		this.handle = handle;
		this.u = u;
		this.v = v;
		this.min = min;
		this.mag = mag;
		this.anisotropy = anisotropy;
		this.maxLod = maxLod;
	}

	static MetalSampler create(long ctx, AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int anisotropy, OptionalDouble maxLod) {
		double lod = maxLod.orElse(1000.0);
		int mipFilter = lod > 0.25 ? 2 : 0;
		long handle = Native.samplerNew(ctx, MetalConst.addressMode(u), MetalConst.addressMode(v), MetalConst.filter(min), MetalConst.filter(mag),
			mipFilter, Math.max(1, anisotropy), (float) Math.max(0.25, lod));
		return new MetalSampler(handle, u, v, min, mag, anisotropy, maxLod);
	}

	@Override
	public AddressMode getAddressModeU() {
		return this.u;
	}

	@Override
	public AddressMode getAddressModeV() {
		return this.v;
	}

	@Override
	public FilterMode getMinFilter() {
		return this.min;
	}

	@Override
	public FilterMode getMagFilter() {
		return this.mag;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.anisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return this.maxLod;
	}

	@Override
	public void close() {
	}
}
