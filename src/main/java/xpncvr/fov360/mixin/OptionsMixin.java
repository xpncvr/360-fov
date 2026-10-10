package xpncvr.fov360.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xpncvr.fov360.Fov360Keys;

import java.util.Arrays;

@Mixin(Options.class)
public abstract class OptionsMixin {

	@Shadow
	@Final
	@Mutable
	public KeyMapping[] keyMappings;

	@Inject(method = "load", at = @At("HEAD"))
	private void fov360$registerKeys(CallbackInfo ci) {
		if (!Arrays.asList(keyMappings).contains(Fov360Keys.OPEN_SETTINGS)) {
			keyMappings = Arrays.copyOf(keyMappings, keyMappings.length + 1);
			keyMappings[keyMappings.length - 1] = Fov360Keys.OPEN_SETTINGS;
		}
	}

	@ModifyArg(
		method = "<init>",
		slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.fov")),
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/OptionInstance$IntRange;<init>(II)V",
			ordinal = 0
		),
		index = 1
	)
	private int panini$raiseFovMax(int maxInclusive) {
		return 400;
	}
}
