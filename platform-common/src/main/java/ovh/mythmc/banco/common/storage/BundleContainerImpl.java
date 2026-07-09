package ovh.mythmc.banco.common.storage;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import ovh.mythmc.banco.api.storage.BancoContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class BundleContainerImpl extends BancoContainer {

    public static final BundleContainerImpl INSTANCE = new BundleContainerImpl();

    // bundle capacity: the total number of "weight units" a bundle can hold
    // Each item occupies (BUNDLE_CAPACITY / item.getMaxStackSize()) units
    // A full stack of 64 costs 1 unit; a stack-of-16 item costs 4 units; a non-stackable
    // (maxStackSize == 1) costs all 64 units, filling the bundle by itself
    static final int BUNDLE_CAPACITY = 64;

    private BundleContainerImpl() {
    }

    @Override
    public String friendlyName() {
        return "BUNDLE";
    }

    @Override
    public Collection<ItemStack> get(UUID uuid) {
        List<ItemStack> items = new ArrayList<>();
        var player = Bukkit.getPlayer(uuid);
        if (player == null) return items;

        if (PlayerInventoryImpl.INSTANCE.isActive())
            items.addAll(getBundlesContent(player.getInventory().getContents()));
        if (EnderChestInventoryImpl.INSTANCE.isActive())
            items.addAll(getBundlesContent(player.getEnderChest().getContents()));
        return items;
    }

    @Override
    protected ItemStack addItem(UUID uuid, ItemStack itemStack) {
        var player = Bukkit.getPlayer(uuid);
        if (player == null || itemStack == null) return itemStack;

        if (PlayerInventoryImpl.INSTANCE.isActive()) {
            itemStack = addToBundlesInContainer(player.getInventory().getContents(), itemStack);
            if (itemStack == null || itemStack.getAmount() <= 0) return null;
        }

        if (EnderChestInventoryImpl.INSTANCE.isActive()) {
            itemStack = addToBundlesInContainer(player.getEnderChest().getContents(), itemStack);
        }

        return (itemStack == null || itemStack.getAmount() <= 0) ? null : itemStack;
    }

    @Override
    protected ItemStack removeItem(UUID uuid, ItemStack itemStack) {
        var player = Bukkit.getPlayer(uuid);
        if (player == null || itemStack == null) return null;

        if (PlayerInventoryImpl.INSTANCE.isActive()) {
            itemStack = removeFromBundlesInContainer(player.getInventory().getContents(), itemStack);
            if (itemStack == null || itemStack.getAmount() <= 0) return null;
        }

        if (EnderChestInventoryImpl.INSTANCE.isActive()) {
            itemStack = removeFromBundlesInContainer(player.getEnderChest().getContents(), itemStack);
        }

        return (itemStack == null || itemStack.getAmount() <= 0) ? null : itemStack;
    }

    /**
     * Collects all non-bundle items stored inside every bundle (and bundles inside
     * shulker boxes) found in {@code container}.
     *
     * <p>Nested bundles are <em>not</em> flattened recursively — only the immediate
     * contents of each bundle are returned, preserving containment semantics.
     * Bundle items that are themselves containers are returned as-is so callers
     * can value them correctly.</p>
     */
    private static List<ItemStack> getBundlesContent(ItemStack[] container) {
        List<ItemStack> content = new ArrayList<>();

        for (ItemStack item : container) {
            if (item == null || item.getType().isAir()) continue;

            if (isBundle(item)) {
                BundleMeta meta = (BundleMeta) item.getItemMeta();
                if (meta != null) {
                    // return copies to avoid mutating shared meta state
                    for (ItemStack inner : meta.getItems()) {
                        if (inner != null && !inner.getType().isAir())
                            content.add(inner.clone());
                    }
                }
            }

            BlockStateMeta shulkerMeta = getShulkerMeta(item);
            if (shulkerMeta != null && shulkerMeta.getBlockState() instanceof ShulkerBox box) {
                for (ItemStack inner : box.getInventory().getContents()) {
                    if (!isBundle(inner)) continue;
                    BundleMeta meta = (BundleMeta) inner.getItemMeta();
                    if (meta == null) continue;
                    for (ItemStack nested : meta.getItems()) {
                        if (nested != null && !nested.getType().isAir())
                            content.add(nested.clone());
                    }
                }
            }
        }

        return content;
    }

    private static ItemStack addToBundlesInContainer(ItemStack[] container, ItemStack itemStack) {
        for (ItemStack item : container) {
            if (item == null || item.getType().isAir()) continue;

            if (isBundle(item)) {
                itemStack = addItemToBundleStack(item, itemStack);
                if (itemStack == null || itemStack.getAmount() <= 0) return null;
            }

            BlockStateMeta meta = getShulkerMeta(item);
            if (meta != null && meta.getBlockState() instanceof ShulkerBox box) {
                Inventory inv = box.getInventory();
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack inner = inv.getItem(i);
                    if (!isBundle(inner)) continue;

                    itemStack = addItemToBundleStack(inner, itemStack);
                    // persist changes
                    inv.setItem(i, inner);
                    meta.setBlockState(box);
                    item.setItemMeta(meta);

                    if (itemStack == null || itemStack.getAmount() <= 0) return null;
                }
            }
        }
        return itemStack;
    }

    private static ItemStack removeFromBundlesInContainer(ItemStack[] container, ItemStack target) {
        for (ItemStack item : container) {
            if (item == null || item.getType().isAir()) continue;

            if (isBundle(item)) {
                if (removeItemFromBundleStack(item, target)) return null;
            }

            BlockStateMeta meta = getShulkerMeta(item);
            if (meta != null && meta.getBlockState() instanceof ShulkerBox box) {
                Inventory inv = box.getInventory();
                for (int i = 0; i < inv.getSize(); i++) {
                    ItemStack inner = inv.getItem(i);
                    if (!isBundle(inner)) continue;

                    if (removeItemFromBundleStack(inner, target)) {
                        inv.setItem(i, inner);
                        meta.setBlockState(box);
                        item.setItemMeta(meta);
                        return null;
                    }
                }
            }
        }
        return target;
    }

    /**
     * Adds as much of {@code toAdd} as the bundle can accept (vanilla capacity),
     * writes the result back into {@code bundleItem}'s meta, and returns whatever
     * could not be inserted (null / amount == 0 means fully consumed).
     */
    private static ItemStack addItemToBundleStack(ItemStack bundleItem, ItemStack toAdd) {
        BundleMeta meta = (BundleMeta) bundleItem.getItemMeta();
        if (meta == null) return toAdd;

        ItemStack result = addItemToBundleMeta(meta, toAdd);
        // validate and normalise before writing back
        normalizeBundleMeta(meta);
        bundleItem.setItemMeta(meta);
        return result;
    }

    /**
     * Removes one matching stack entry from {@code bundleItem}'s meta.
     * Returns {@code true} if anything was removed.
     */
    private static boolean removeItemFromBundleStack(ItemStack bundleItem, ItemStack toRemove) {
        BundleMeta meta = (BundleMeta) bundleItem.getItemMeta();
        if (meta == null) return false;

        boolean removed = removeItemFromBundleMeta(meta, toRemove);
        if (removed) {
            // validate and normalise BEFORE writing back
            normalizeBundleMeta(meta);
            bundleItem.setItemMeta(meta);
        }
        return removed;
    }

    /**
     * Attempts to add {@code itemStack} into {@code meta}, respecting the vanilla
     * bundle capacity model:
     * <ul>
     *   <li>Each item occupies {@code BUNDLE_CAPACITY / item.getMaxStackSize()} weight units.</li>
     *   <li>The bundle is full once the sum of all occupied units reaches {@link #BUNDLE_CAPACITY}.</li>
     * </ul>
     *
     * <p>Nested bundles are not extracted; they are inserted as a single item
     * and consume the appropriate weight (always 64 units = full bundle, because
     * bundles are non-stackable). This correctly preserves containment semantics.</p>
     *
     * @param meta      the bundle meta to modify (mutated in place)
     * @param itemStack the item to add
     * @return the leftover stack that could not fit, or {@code null} if fully consumed
     */
    static ItemStack addItemToBundleMeta(BundleMeta meta, ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) return null;

        // defensive copy
        List<ItemStack> contents = defensiveCopy(meta.getItems());

        int itemWeight = itemWeight(itemStack);  // weight per single item
        int pending = itemStack.getAmount();

        // phase 1: top up existing matching stacks first
        for (ItemStack inside : contents) {
            if (pending <= 0) break;
            if (!inside.isSimilar(itemStack)) continue;

            int usedUnits = occupiedUnits(contents);
            int freeUnits = BUNDLE_CAPACITY - usedUnits;
            if (freeUnits <= 0) break;

            // how many more of this item can fit by weight?
            int canFitByWeight = freeUnits / itemWeight;
            // how many more can fit in this stack slot?
            int canFitInSlot = inside.getMaxStackSize() - inside.getAmount();

            int canAdd = Math.min(Math.min(canFitByWeight, canFitInSlot), pending);
            if (canAdd <= 0) continue;

            inside.setAmount(inside.getAmount() + canAdd);
            pending -= canAdd;
        }

        // phase 2: open a new slot if still items remaining
        if (pending > 0) {
            int usedUnits = occupiedUnits(contents);
            int freeUnits = BUNDLE_CAPACITY - usedUnits;
            if (freeUnits >= itemWeight) {
                int canFitByWeight = freeUnits / itemWeight;
                int canAdd = Math.min(Math.min(canFitByWeight, itemStack.getMaxStackSize()), pending);
                if (canAdd > 0) {
                    ItemStack newSlot = itemStack.clone();
                    newSlot.setAmount(canAdd);
                    contents.add(newSlot);
                    pending -= canAdd;
                }
            }
        }

        // strip air/entries with no amount then write back
        meta.setItems(contents.stream()
                .filter(i -> i != null && !i.getType().isAir() && i.getAmount() > 0)
                .toList());

        if (pending <= 0) return null;
        ItemStack remainder = itemStack.clone();
        remainder.setAmount(pending);
        return remainder;
    }

    /**
     * Removes up to {@code target.getAmount()} items matching {@code target} from
     * the bundle meta.
     *
     * @param meta   the bundle meta to modify (mutated in place)
     * @param target the item type/amount to remove
     * @return {@code true} if at least one item was removed
     */
    static boolean removeItemFromBundleMeta(BundleMeta meta, ItemStack target) {
        // copy to avoid mutating shared references
        List<ItemStack> contents = defensiveCopy(meta.getItems());
        boolean changed = false;

        for (int i = 0; i < contents.size(); i++) {
            ItemStack inside = contents.get(i);
            if (!inside.isSimilar(target)) continue;

            int removeAmount = Math.min(inside.getAmount(), target.getAmount());
            inside.setAmount(inside.getAmount() - removeAmount);
            changed = true;

            if (inside.getAmount() <= 0) {
                contents.remove(i);
            }
            // remove only the first matching slot
            break;
        }

        if (changed) {
            meta.setItems(contents.stream()
                    .filter(i -> i != null && !i.getType().isAir() && i.getAmount() > 0)
                    .toList());
        }
        return changed;
    }

    /**
     * Ensures the bundle's contents never exceed {@link #BUNDLE_CAPACITY} weight units.  Any items that would cause overflow
     * are trimmed (last in, first out by list order) and discarded.
     */

    static void normalizeBundleMeta(BundleMeta meta) {
        List<ItemStack> contents = defensiveCopy(meta.getItems());
        List<ItemStack> normalised = new ArrayList<>();
        int usedUnits = 0;

        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;

            int weight = itemWeight(item);
            int maxByCapacity = (BUNDLE_CAPACITY - usedUnits) / weight;
            if (maxByCapacity <= 0) {
                // noroom at all, drop slot to prevent overflow
                continue;
            }

            int allowed = Math.min(item.getAmount(), maxByCapacity);
            ItemStack kept = item.clone();
            kept.setAmount(allowed);
            normalised.add(kept);
            usedUnits += weight * allowed;

            if (usedUnits >= BUNDLE_CAPACITY) break;
        }

        meta.setItems(normalised);
    }

    /**
     * Returns the weight (in bundle units) of a single item of the given
     * stack, following the vanilla rule: weight = {@link #BUNDLE_CAPACITY} / item.getMaxStackSize()
     * Integer division is used; the minimum returned is 1.
     */
    static int itemWeight(ItemStack item) {
        int maxStack = item.getMaxStackSize();
        if (maxStack <= 0) return BUNDLE_CAPACITY; // non-stackable
        return Math.max(1, BUNDLE_CAPACITY / maxStack);
    }

    /**
     * Returns the total weight units currently occupied by all items in the list.
     */
    static int occupiedUnits(List<ItemStack> contents) {
        int total = 0;
        for (ItemStack item : contents) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            total += itemWeight(item) * item.getAmount();
        }
        return total;
    }

    /**
     * Returns the number of weight units still available in the bundle.
     */
    static int bundleFreeUnits(BundleMeta meta) {
        return Math.max(0, BUNDLE_CAPACITY - occupiedUnits(meta.getItems()));
    }

    /**
     * Returns independent ItemStack clones so that
     * mutations on the list or individual stacks do not affect the original meta
     * contents or any other caller holding references to the same stacks.
     */
    private static List<ItemStack> defensiveCopy(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null) copy.add(item.clone());
        }
        return copy;
    }

    private static boolean isBundle(ItemStack item) {
        return item != null && item.getType().name().contains("BUNDLE");
    }

    private static boolean isShulkerBox(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        return item.getType().name().endsWith("SHULKER_BOX");
    }

    private static BlockStateMeta getShulkerMeta(ItemStack item) {
        if (!isShulkerBox(item)) return null;
        try {
            var meta = item.getItemMeta();
            if (!(meta instanceof BlockStateMeta)) {
                item.setItemMeta(Bukkit.getItemFactory().asMetaFor(meta, item.getType()));
                meta = item.getItemMeta();
            }
            return (meta instanceof BlockStateMeta bsm) ? bsm : null;
        } catch (Exception e) {
            return null;
        }
    }
}
