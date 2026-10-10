package xpncvr.fov360.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xpncvr.fov360.Fov360OptionsScreen;

@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

	@Unique
	private static final Identifier FOV360_ICON = Identifier.fromNamespaceAndPath("fov360", "icon/settings");

	@Unique
	private static final int FOV360_ICON_SIZE = 20;

	@Unique
	private static final int FOV360_ICON_GAP = 8;

	@Unique
	private LayoutElement fov360$fovSlider;

	@Unique
	private SpriteIconButton fov360$settingsButton;

	private OptionsScreenMixin(Component title) {
		super(title);
	}

	@WrapOperation(
		method = "init",
		slice = @Slice(from = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;fov()Lnet/minecraft/client/OptionInstance;")),
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/layouts/LinearLayout;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;)Lnet/minecraft/client/gui/layouts/LayoutElement;",
			ordinal = 0
		)
	)
	private LayoutElement fov360$captureFovSlider(LinearLayout row, LayoutElement fovSlider, Operation<LayoutElement> original) {
		fov360$fovSlider = fovSlider;
		return original.call(row, fovSlider);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void fov360$addSettingsButton(CallbackInfo ci) {
		if (fov360$fovSlider == null) {
			return;
		}
		Screen self = this;
		fov360$settingsButton = addRenderableWidget(SpriteIconButton.builder(Component.translatable("fov360.options.title"),
				button -> minecraft.gui.setScreen(new Fov360OptionsScreen(self, minecraft.options)), true)
			.width(FOV360_ICON_SIZE)
			.sprite(FOV360_ICON, 16, 16)
			.withTootip()
			.build());
		fov360$positionSettingsButton();
	}

	@Inject(method = "repositionElements", at = @At("TAIL"))
	private void fov360$repositionSettingsButton(CallbackInfo ci) {
		fov360$positionSettingsButton();
	}

	@Unique
	private void fov360$positionSettingsButton() {
		if (fov360$settingsButton == null || fov360$fovSlider == null) {
			return;
		}
		fov360$settingsButton.setPosition(
			fov360$fovSlider.getX() - FOV360_ICON_GAP - FOV360_ICON_SIZE,
			fov360$fovSlider.getY() + (fov360$fovSlider.getHeight() - FOV360_ICON_SIZE) / 2);
	}
}
