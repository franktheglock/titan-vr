package dev.titanvr.paper;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.net.URI;

/** Registers the bundled datapack (the "Titan VR Arena" world type) before worlds are created. */
public class TitanPaperBootstrap implements PluginBootstrap {
	@Override
	public void bootstrap(BootstrapContext context) {
		context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY, event -> {
			try {
				URI uri = getClass().getResource("/titanvr_datapack").toURI();
				event.registrar().discoverPack(uri, "titanvr");
			} catch (Exception e) {
				context.getLogger().error("Could not register the Titan VR datapack", e);
			}
		});
	}
}
