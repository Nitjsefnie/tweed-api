package de.siphalor.tweed.tailor;

import de.siphalor.tweed.Tweed;
import de.siphalor.tweed.client.CustomNoticeScreen;
import de.siphalor.tweed.client.TweedClient;
import de.siphalor.tweed.config.*;
import de.siphalor.tweed.config.constraints.ConstraintException;
import de.siphalor.tweed.config.entry.ValueConfigEntry;
import io.github.prospector.modmenu.api.ConfigScreenFactory;
import io.netty.buffer.Unpooled;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.DropdownBoxEntry;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.network.ClientSidePacketRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.NoticeScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.PacketByteBuf;

import java.awt.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Environment(EnvType.CLIENT)
public class ClothTailor extends Tailor {
	public static final ClothTailor INSTANCE = new ClothTailor();

	protected static final String SCREEN_NAME_PREFIX = "tweed.cloth.";
	protected boolean waitingForFile;

	private static final Map<Class<?>, EntryConverter<?>> ENTRY_CONVERTERS = new HashMap<>();
	private Map<String, ConfigScreenFactory<?>> screenFactories = new HashMap<>();

	@Override
	public void process(ConfigFile configFile) {
		String modId = configFile.getName();
		ClothData clothData = configFile.getTailorAnnotation(ClothData.class);
		if (clothData != null && !clothData.modid().isEmpty()) {
			modId = clothData.modid();
		}
		screenFactories.put(modId, parent -> convert(configFile, parent));
	}

	public Map<String, ConfigScreenFactory<?>> getScreenFactories() {
		return screenFactories;
	}

	public Screen convert(ConfigFile configFile, Screen parentScreen) {
		boolean inGame = MinecraftClient.getInstance().world != null;
		if (inGame && configFile.getRootCategory().getEnvironment() != ConfigEnvironment.CLIENT) {
			return new CustomNoticeScreen(
					() -> {
						waitingForFile = true;

						PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
						buf.writeString(configFile.getName());
						buf.writeEnumConstant(ConfigEnvironment.UNIVERSAL);
						buf.writeEnumConstant(ConfigScope.SMALLEST);
						buf.writeEnumConstant(ConfigOrigin.MAIN);
						ClientSidePacketRegistry.INSTANCE.sendToServer(Tweed.REQUEST_SYNC_C2S_PACKET, buf);

						TweedClient.setSyncRunnable(() -> {
							if (waitingForFile) {
								waitingForFile = false;
								MinecraftClient.getInstance().openScreen(buildConfigScreen(configFile, parentScreen));
							}
						});
					},
					() -> {
						waitingForFile = false;
						MinecraftClient.getInstance().openScreen(parentScreen);
					},
					new TranslatableText("tweed.gui.screen.syncFromServer"),
					new TranslatableText("tweed.gui.screen.syncFromServer.note")
			);
		} else {
			return buildConfigScreen(configFile, parentScreen);
		}
	}

	public Screen buildConfigScreen(ConfigFile configFile, Screen parentScreen) {
		final String path = SCREEN_NAME_PREFIX + configFile.getName();
		ConfigBuilder configBuilder = ConfigBuilder.create();
		configBuilder.setParentScreen(parentScreen);
		if (configFile.getRootCategory().getBackgroundTexture() != null) {
			configBuilder.setDefaultBackgroundTexture(configFile.getRootCategory().getBackgroundTexture());
		}
		configBuilder.setSavingRunnable(() -> save(configFile));
		configBuilder.setTitle(I18n.translate(path));

		if (configFile.getRootCategory().entryStream().allMatch(entry -> entry.getValue() instanceof ConfigCategory)) {
			configFile.getRootCategory().entryStream().forEach(entry -> createCategory(configBuilder, (ConfigCategory) entry.getValue(), path + "." + entry.getKey()));
		} else {
			createCategory(configBuilder, configFile.getRootCategory(), path + ".main");
		}

		return configBuilder.build();
	}

	private void convertCategory(ConfigEntryBuilder entryBuilder, Consumer<AbstractConfigListEntry<?>> registry, ConfigCategory configCategory, String path) {
		configCategory.entryStream().forEachOrdered(entry -> {
			final String subPath = path + "." + entry.getKey();

			if (entry.getValue() instanceof ConfigCategory) {
				SubCategoryBuilder categoryBuilder = entryBuilder.startSubCategory(I18n.translate(subPath));
				categoryBuilder.add(entryBuilder.startTextDescription(entry.getValue().getDescription()).setColor(Color.GRAY.getRGB()).build());

				convertCategory(entryBuilder, categoryBuilder::add, (ConfigCategory) entry.getValue(), subPath);

				registry.accept(categoryBuilder.build());

			} else if (entry.getValue() instanceof ValueConfigEntry<?>) {
				Class<?> clazz = ((ValueConfigEntry<?>) entry.getValue()).getType();
				EntryConverter<?> entryConverter;

				entryConverter = ENTRY_CONVERTERS.get(clazz);
				main:
				while (clazz != Object.class && entryConverter == null) {
					for (Class<?> anInterface : clazz.getInterfaces()) {
						entryConverter = ENTRY_CONVERTERS.get(anInterface);
						if (entryConverter != null) {
							break main;
						}
					}

					clazz = clazz.getSuperclass();
					entryConverter = ENTRY_CONVERTERS.get(clazz);
				}

				if (entryConverter != null) {
					//noinspection unchecked,rawtypes,rawtypes
					registry.accept(entryConverter.convert((ValueConfigEntry) entry.getValue(), entryBuilder, subPath));
				} else {
					Tweed.LOGGER.warn("Couldn't convert config entry of type " + ((ValueConfigEntry<?>) entry.getValue()).getType().getSimpleName() + " to cloth entry!");
				}
			}
		});
	}

	private void createCategory(ConfigBuilder configBuilder, ConfigCategory configCategory, String name) {
		me.shedaniel.clothconfig2.api.ConfigCategory clothCategory = configBuilder.getOrCreateCategory(I18n.translate(name));
		if (configCategory.getBackgroundTexture() != null) {
			clothCategory.setCategoryBackground(configCategory.getBackgroundTexture());
		}
		clothCategory.addEntry(configBuilder.entryBuilder().startTextDescription(configCategory.getComment()).setColor(Color.GRAY.getRGB()).build());
		convertCategory(configBuilder.entryBuilder(), clothCategory::addEntry, configCategory, name);
	}

	private void save(ConfigFile configFile) {
		if (MinecraftClient.getInstance().world != null) {
			configFile.syncToServer(ConfigEnvironment.UNIVERSAL, ConfigScope.SMALLEST);
			ConfigLoader.updateMainConfigFile(configFile, ConfigEnvironment.UNIVERSAL, ConfigScope.HIGHEST);
			ConfigLoader.loadConfigs(MinecraftClient.getInstance().getResourceManager(), ConfigEnvironment.UNIVERSAL, ConfigScope.SMALLEST);
		} else {
			ConfigLoader.updateMainConfigFile(configFile, ConfigEnvironment.UNIVERSAL, ConfigScope.HIGHEST);
			ConfigLoader.loadConfigs(MinecraftClient.getInstance().getResourceManager(), ConfigEnvironment.UNIVERSAL, ConfigScope.WORLD);
		}
	}

	public static <V> void registerEntryConverter(Class<V> valueType, EntryConverter<V> converter) {
		ENTRY_CONVERTERS.put(valueType, converter);
	}

	public static <V> Optional<String> errorSupplier(V value, ValueConfigEntry<V> configEntry) {
		try {
			configEntry.applyConstraints(value);
		} catch (ConstraintException e) {
			return Optional.of(e.getMessage());
		}
		return Optional.empty();
	}

	@FunctionalInterface
	public interface EntryConverter<V> {
		AbstractConfigListEntry<?> convert(ValueConfigEntry<V> configEntry, ConfigEntryBuilder entryBuilder, String langKey);
	}

	static {
		registerEntryConverter(Boolean.class, (configEntry, entryBuilder, langKey) ->
				entryBuilder.startBooleanToggle(I18n.translate(langKey), configEntry.getMainConfigValue())
						.setDefaultValue(configEntry::getDefaultValue)
						.setSaveConsumer(configEntry::setMainConfigValue)
						.setTooltipSupplier(configEntry::getClothyDescription)
						.setErrorSupplier(value -> errorSupplier(value, configEntry))
						.build()
		);
		//noinspection unchecked
		registerEntryConverter(DropdownMaterial.class, (configEntry, entryBuilder, langKey) ->
				entryBuilder.startDropdownMenu(
						I18n.translate(langKey),
						configEntry.getMainConfigValue(),
						input -> {
							//noinspection unchecked
							for (DropdownMaterial<?> value : ((Collection<DropdownMaterial<?>>) configEntry.getDefaultValue().values())) {
								if (I18n.translate(value.getTranslationKey()).equals(input)) {
									return value;
								}
							}
							return null;
						},
						dropdownMaterial -> I18n.translate(dropdownMaterial.getTranslationKey()),
						new DropdownBoxEntry.DefaultSelectionCellCreator<>(dropdownMaterial -> I18n.translate(dropdownMaterial.getTranslationKey()))
				)
						.setDefaultValue(configEntry::getDefaultValue)
						.setSaveConsumer(o -> configEntry.setMainConfigValue((DropdownMaterial<?>) o))
						.setTooltipSupplier(configEntry::getClothyDescription)
						.setErrorSupplier(value -> errorSupplier((DropdownMaterial<?>) value, configEntry))
						.setSelections(configEntry.getDefaultValue().values())
						.build()
		);
		registerEntryConverter(Enum.class, (configEntry, entryBuilder, langKey) ->
				entryBuilder.startEnumSelector(I18n.translate(langKey), configEntry.getType(), configEntry.getMainConfigValue())
						.setDefaultValue(configEntry::getDefaultValue)
						.setSaveConsumer(o -> configEntry.setMainConfigValue((Enum<?>) o))
						.setTooltipSupplier(configEntry::getClothyDescription)
						.setErrorSupplier(value -> errorSupplier((Enum<?>) value, configEntry))
						.build()
		);
		registerEntryConverter(Float.class, (configEntry, entryBuilder, langKey) ->
				entryBuilder.startFloatField(I18n.translate(langKey), configEntry.getMainConfigValue())
						.setDefaultValue(configEntry::getDefaultValue)
						.setSaveConsumer(configEntry::setMainConfigValue)
						.setTooltipSupplier(configEntry::getClothyDescription)
						.setErrorSupplier(value -> errorSupplier(value, configEntry))
						.build()
		);
		registerEntryConverter(Integer.class, (configEntry, entryBuilder, langKey) ->
				entryBuilder.startIntField(I18n.translate(langKey), configEntry.getMainConfigValue())
						.setDefaultValue(configEntry::getDefaultValue)
						.setSaveConsumer(configEntry::setMainConfigValue)
						.setTooltipSupplier(configEntry::getClothyDescription)
						.setErrorSupplier(value -> errorSupplier(value, configEntry))
						.build()
		);
		registerEntryConverter(String.class, (configEntry, entryBuilder, langKey) ->
				entryBuilder.startStrField(I18n.translate(langKey), configEntry.getMainConfigValue())
						.setDefaultValue(configEntry::getDefaultValue)
						.setSaveConsumer(configEntry::setMainConfigValue)
						.setTooltipSupplier(configEntry::getClothyDescription)
						.setErrorSupplier(value -> errorSupplier(value, configEntry))
						.build()
		);
	}
}
