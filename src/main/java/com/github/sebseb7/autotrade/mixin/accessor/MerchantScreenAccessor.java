package com.github.sebseb7.autotrade.mixin.accessor;

import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MerchantScreen.class)
public interface MerchantScreenAccessor {
	@Accessor("indexStartOffset")
	int getIndexStartOffset();
}
