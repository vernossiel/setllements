package net.mca.network.s2c;

import net.mca.ClientProxy;
import net.mca.network.NbtDataMessage;
import net.mca.resources.BuildingTypes;
import net.mca.resources.Rank;
import net.mca.resources.Tasks;
import net.mca.resources.data.BuildingType;
import net.mca.resources.data.tasks.Task;
import net.mca.server.world.data.Village;

import java.io.Serial;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GetVillageResponse extends NbtDataMessage {
    @Serial
    private static final long serialVersionUID = 4882425683460617550L;

    public final Rank rank;
    public final int reputation;
    public final boolean isVillage;
    public final Set<String> ids;
    public final Map<Rank, List<Task>> tasks;
    public final Map<String, BuildingType> buildingTypes;

    public GetVillageResponse(Village data, Rank rank, int reputation, boolean isVillage, Set<String> ids) {
        super(data.save());
        this.rank = rank;
        this.reputation = reputation;
        this.isVillage = isVillage;
        this.ids = ids;
        this.tasks = Tasks.getInstance().tasks;
        this.buildingTypes = BuildingTypes.getInstance().getServerBuildingTypes();
    }

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleVillageDataResponse(this);
    }
}
