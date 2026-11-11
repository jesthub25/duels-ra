package org.jestron.duelsira.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/** ItemStack[] <-> Base64. */
public final class InventorySerializer {

    private InventorySerializer() {
    }

    public static String toBase64(ItemStack[] items) {
        if (items == null) items = new ItemStack[0];
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             BukkitObjectOutputStream data = new BukkitObjectOutputStream(out)) {
            data.writeInt(items.length);
            for (ItemStack item : items) {
                data.writeObject(item);
            }
            data.flush();
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Envanter serilestirilemedi", e);
        }
    }

    public static ItemStack[] fromBase64(String base64) {
        if (base64 == null || base64.isEmpty()) return new ItemStack[0];
        try (ByteArrayInputStream in = new ByteArrayInputStream(Base64.getMimeDecoder().decode(base64));
             BukkitObjectInputStream data = new BukkitObjectInputStream(in)) {
            int size = data.readInt();
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                items[i] = (ItemStack) data.readObject();
            }
            return items;
        } catch (Exception e) {
            throw new IllegalStateException("Envanter cozulemedi", e);
        }
    }
}
