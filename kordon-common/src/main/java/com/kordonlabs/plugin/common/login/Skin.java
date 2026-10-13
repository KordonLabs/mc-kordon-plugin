package com.kordonlabs.plugin.common.login;

/**
 * The skin of an online-mode profile, sent with {@code /login}: a {@code textures.minecraft.net} texture URL and the
 * model ("slim" or "classic").
 */
public record Skin(String texture, String model) {
}
