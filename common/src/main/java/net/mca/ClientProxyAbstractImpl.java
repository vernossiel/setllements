package net.mca;

import net.mca.network.ClientInteractionManager;
import net.mca.network.ClientInteractionManagerImpl;

/**
 * Workaround for Forge's BS
 */
public abstract class ClientProxyAbstractImpl extends ClientProxy.Impl {

    private final ClientInteractionManager networkHandler = new ClientInteractionManagerImpl();

    @Override
    public final ClientInteractionManager getNetworkHandler() {
        return networkHandler;
    }
}
