package io.kanon.specctl.core.plugin;

import io.kanon.specctl.core.platform.PlatformTypes;

import java.util.ArrayList;
import java.util.List;

public final class BuiltinPlugins {
    private BuiltinPlugins() {
    }

    public static List<PluginRuntime.RegisteredPlugin> defaults() {
        return forCapabilities(new PlatformTypes.CapabilitySet(true, true, true, true, true));
    }

    public static List<PluginRuntime.RegisteredPlugin> forCapabilities(PlatformTypes.CapabilitySet capabilities) {
        List<PluginRuntime.RegisteredPlugin> plugins = new ArrayList<>();
        plugins.add(PluginSupport.registered(PluginSupport.bootstrapManifest(), new BootstrapPlugin()));
        plugins.add(PluginSupport.registered(PluginSupport.domainManifest(), new DomainPlugin()));
        plugins.add(PluginSupport.registered(PluginSupport.runtimeManifest(), new RuntimePlugin()));
        plugins.add(PluginSupport.registered(PluginSupport.apiManifest(), new ApiPlugin()));
        plugins.add(PluginSupport.registered(PluginSupport.contractManifest(), new ContractPlugin()));
        if (capabilities.postgres()) {
            plugins.add(PluginSupport.registered(PluginSupport.persistenceManifest(), new PersistencePlugin()));
        }
        if (capabilities.messaging()) {
            plugins.add(PluginSupport.registered(PluginSupport.messagingManifest(), new MessagingPlugin()));
        }
        if (capabilities.security()) {
            plugins.add(PluginSupport.registered(PluginSupport.securityManifest(), new SecurityPlugin()));
        }
        if (capabilities.observability()) {
            plugins.add(PluginSupport.registered(PluginSupport.observabilityManifest(), new ObservabilityPlugin()));
        }
        return plugins;
    }
}
