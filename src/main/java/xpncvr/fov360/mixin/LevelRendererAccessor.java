package xpncvr.fov360.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

	@Accessor("entityOutlineTarget")
	RenderTarget panini$getEntityOutlineTarget();

	@Mutable
	@Accessor("entityOutlineTarget")
	void panini$setEntityOutlineTarget(RenderTarget target);

	@Accessor("currentFrameRendersEntityOutline")
	boolean panini$getCurrentFrameRendersEntityOutline();

	@Accessor("currentFrameRendersEntityOutline")
	void panini$setCurrentFrameRendersEntityOutline(boolean value);
}
