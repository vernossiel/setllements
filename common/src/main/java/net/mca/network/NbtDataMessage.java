package net.mca.network;

import net.mca.cobalt.network.Message;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;

import java.io.*;

public abstract class NbtDataMessage implements Message {
    @Serial
    private static final long serialVersionUID = 3409849549326097419L;

    private final Data data;

    public NbtDataMessage(NbtCompound data) {
        this.data = new Data(data);
    }

    public NbtCompound getData() {
        return data.nbt;
    }

    private static final class Data implements Serializable {
        @Serial
        private static final long serialVersionUID = 5728742776742369248L;

        transient NbtCompound nbt;

        Data(NbtCompound nbt) {
            this.nbt = nbt;
        }

        @Serial
        private void writeObject(ObjectOutputStream out) throws IOException {
            NbtIo.write(nbt, out);
        }

        @Serial
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            nbt = NbtIo.read(in);
        }
    }
}
