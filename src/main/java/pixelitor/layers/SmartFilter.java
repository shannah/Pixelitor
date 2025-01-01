/*
 * Copyright 2025 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.layers;

import pixelitor.Composition;
import pixelitor.CopyType;
import pixelitor.filters.Filter;
import pixelitor.filters.gui.FilterWithGUI;
import pixelitor.filters.util.FilterAction;
import pixelitor.filters.util.FilterSearchPanel;
import pixelitor.gui.utils.GUIUtils;
import pixelitor.gui.utils.TaskAction;
import pixelitor.history.FilterChangedEdit;
import pixelitor.history.History;
import pixelitor.utils.Icons;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.debug.DebugNode;

import javax.swing.*;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serial;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A specialized layer within a smart object that applies a filter
 * non-destructively. Unlike regular adjustment layers, they don't
 * have to execute their filter whenever an unrelated layer is changed,
 * because their output is cached inside the smart object.
 * Additionally, smart filters also cache their own output,
 * so that if the filter settings are changed, only the filters
 * downstream from that filter will be rerun.
 */
public class SmartFilter extends AdjustmentLayer implements ImageSource {
    @Serial
    private static final long serialVersionUID = 1L;

    // the source of the input image, for example the previous
    // smart filter in the chain or the composition if this is the first
    private ImageSource imageSource;

    private transient BufferedImage outputCache;
    private SmartObject smartObject; // the parent

    // the next smart filter in the chain (null if this is the last filter)
    private SmartFilter next;

    // static field used for the copy-paste of smart filters
    public static SmartFilter copiedSmartFilter;

    public SmartFilter(Filter filter, ImageSource imageSource, SmartObject smartObject) {
        super(smartObject.getComp(), filter.getName(), filter);
        setImageSource(imageSource);
        setSmartObject(smartObject);
        holder = smartObject;
    }

    public SmartFilter(SmartFilter orig, Composition newComp, String name) {
        super(newComp, name, orig.getFilter().copy());

        this.imageSource = orig.imageSource;
        this.smartObject = orig.smartObject;

        holder = smartObject;
        outputCache = orig.outputCache; // safe to share
    }

    @Serial
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        // defaults for transient fields
        outputCache = null;

        in.defaultReadObject();

        // migrate
        holder = smartObject;
    }

    @Override
    protected SmartFilter createTypeSpecificCopy(CopyType copyType, Composition newComp) {
        return new SmartFilter(this, newComp, copyType.createLayerCopyName(name));
    }

    @Override
    protected BufferedImage adjustImage(BufferedImage currentComposite,
                                        boolean firstVisibleLayer) {
        // Smart filters don't use the normal layer painting
        // mechanism, therefore this methid is never called.
        throw new IllegalStateException();
    }

    @Override
    public BufferedImage getImage() {
        BufferedImage prevImage = imageSource.getImage();
        if (!isVisible()) {
            return prevImage;
        }

        BufferedImage transformed = transformImage(prevImage);

        if (usesMask()) {
            // copy, because otherwise different masks
            // are applied to the same cached image
            transformed = ImageUtils.copyImage(transformed);

            mask.applyTo(transformed);
        }
        if (!usesMask() && isNormalAndOpaque()) {
            return transformed;
        } else {
            // Unlike an adjustment layer, this makes sure that prevImage
            // (which could be cached in the image source) isn't modified.
            BufferedImage copy = ImageUtils.copyImage(prevImage);

            Graphics2D g = copy.createGraphics();
            setupComposite(g, false);
            g.drawImage(transformed, 0, 0, null);
            g.dispose();
            return copy;
        }
    }

    @Override
    public BufferedImage transformImage(BufferedImage src) {
        if (outputCache != null) {
            return outputCache;
        }
        assert src != null;
        createOutputCache(src);

        return outputCache;
    }

    public void evaluateNow() {
        if (outputCache == null) {
            createOutputCache(imageSource.getImage());
        }
    }

    private void createOutputCache(BufferedImage src) {
        outputCache = filter.transformImage(src);
        if (outputCache == src) {
            outputCache = ImageUtils.copyImage(outputCache);
        }
    }

    @Override
    public void setVisible(boolean newVisibility, boolean addToHistory, boolean update) {
        super.setVisible(newVisibility, addToHistory, false);
        if (update) {
            layerLevelSettingsChanged(true);
        }
    }

    @Override
    protected void maskChanged() {
        layerLevelSettingsChanged(false);
    }

    @Override
    public void setOpacity(float newOpacity, boolean addToHistory, boolean update) {
        if (opacity == newOpacity) {
            return;
        }
        super.setOpacity(newOpacity, addToHistory, false);
        if (update) {
            layerLevelSettingsChanged(true);
        }
    }

    @Override
    public void setBlendingMode(BlendingMode newMode, boolean addToHistory, boolean update) {
        if (blendingMode == newMode) {
            return;
        }
        super.setBlendingMode(newMode, addToHistory, false);
        if (update) {
            layerLevelSettingsChanged(true);
        }
    }

    /**
     * Invalidates the cache for this filter and all subsequent filters in the chain.
     */
    public void invalidateChain() {
        invalidateCache();
        if (next != null) {
            //noinspection TailRecursion
            next.invalidateChain();
        }
    }

    /**
     * Clears the cached filtered image, forcing recomputation on next access.
     */
    public void invalidateCache() {
        if (outputCache != null) {
            outputCache.flush();
            outputCache = null;
        }
    }

    @Override
    public void setFilter(Filter filter) {
        this.filter = filter;

        invalidateAll();
        holder.update();
    }

    /**
     * Invalidates both the filter chain and the parent smart object's cache.
     */
    public void invalidateAll() {
        invalidateChain();
        smartObject.invalidateImageCache();
    }

    /**
     * Only invalidates downstream filters and the smart object cache.
     */
    private void invalidateAllButTheCache() {
        // invalidate only starting from the next one
        if (next != null) {
            next.invalidateChain();
        }
        smartObject.invalidateImageCache();
    }

    /**
     * Called when "layer level" settings have changed (such
     * as the blending mode, opacity, visibility, layer mask),
     * but not the filter settings.
     */
    public void layerLevelSettingsChanged(boolean update) {
        invalidateAllButTheCache();
        if (update) {
            holder.update();
            smartObject.updateIconImage();
        }
    }

    @Override
    public void onFilterDialogAccepted(String filterName) {
        super.onFilterDialogAccepted(filterName);
        smartObject.updateIconImage();
    }

    @Override
    public void onFilterDialogCanceled() {
        boolean changed = filterSettingsChanged();
        super.onFilterDialogCanceled();
        if (changed) {
            // Force recalculating the image if this dialog session
            // changed the filter (and therefore the image).
            invalidateAll();
        }
    }

    @Override
    public void startPreview(Filter filter, boolean firstPreview, Component busyCursorTarget) {
        if (!firstPreview) {
            invalidateAll();
        }
        if (outputCache != null) {
            // the painting thread already calculated it
            return;
        }
        GUIUtils.runWithBusyCursor(() ->
                createOutputCache(imageSource.getImage()),
            busyCursorTarget);
        holder.update();
    }

    @Override
    public JPopupMenu createLayerIconPopupMenu() {
        // just create the popup menu from scratch, since the
        // superclasses don't add anything to it
        JPopupMenu popup = new JPopupMenu();

        if (filter instanceof FilterWithGUI) {
            popup.add(new TaskAction("Edit " + getName() + "...", this::edit));
        }
        popup.add(new TaskAction("Delete " + getName(), () ->
            smartObject.deleteSmartFilter(this, true, true)));
        popup.add(new TaskAction("Copy " + getName(), () ->
            copiedSmartFilter = (SmartFilter) copy(CopyType.UNDO, true, comp)));

        if (!hasMask()) {
            popup.add(new TaskAction("Add Layer Mask", () -> addMask(false)));
        }

        popup.add(new TaskAction("Replace Filter...", this::replaceFilter));

        int numSmartFilters = smartObject.getNumSmartFilters();
        if (numSmartFilters > 1) {
            int index = smartObject.indexOf(this);
            boolean canMoveUp = index < numSmartFilters - 1;
            boolean canMoveDown = index > 0;
            if (canMoveUp && canMoveDown) {
                popup.addSeparator();
            }
            if (canMoveUp) {
                popup.add(new TaskAction("Move Up", Icons.getUpArrowIcon(), () ->
                    smartObject.moveUp(this)));
            }
            if (canMoveDown) {
                popup.add(new TaskAction("Move Down", Icons.getDownArrowIcon(), () ->
                    smartObject.moveDown(this)));
            }
        }

        return popup;
    }

    public void shapeDraggedOnMask() {
        smartObject.invalidateImageCache();
    }

    private void replaceFilter() {
        FilterAction action = FilterSearchPanel.showInDialog("Replace " + filter.getName());
        if (action == null) {
            return; // dialog canceled
        }
        Filter origFilter = filter;
        String origName = getName();

        filter = action.createNewFilterInstance();
        setName(filter.getName(), false);

        History.add(new FilterChangedEdit(this, origFilter, origName));

        invalidateAll();
        holder.update();
        edit();
    }

    public ImageSource getImageSource() {
        return imageSource;
    }

    public void setImageSource(ImageSource imageSource) {
        this.imageSource = Objects.requireNonNull(imageSource);
    }

    public SmartObject getSmartObject() {
        return smartObject;
    }

    public void setSmartObject(SmartObject smartObject) {
        this.smartObject = Objects.requireNonNull(smartObject);
        this.holder = smartObject;
    }

    @Override
    public void setHolder(LayerHolder holder) {
        setSmartObject((SmartObject) holder);
    }

    @Override
    public LayerHolder getHolderForNewLayers() {
        // don't try to add regular layers inside a smart object
        // just because a smart filter is selected.
        return smartObject.getHolderForNewLayers();
    }

    public SmartFilter getNext() {
        return next;
    }

    public void setNext(SmartFilter next) {
        assert next != this;
        this.next = next;
    }

    private Stream<SmartFilter> getChainStream() {
        return Stream.iterate(this, Objects::nonNull, SmartFilter::getNext);
    }

    public String debugChain() {
        return getChainStream()
            .limit(5) // prevent infinite recursion
            .map(SmartFilter::toString)
            .collect(Collectors.joining(", ", "[", "]"));
    }

    public boolean hasCachedImage() {
        return outputCache != null;
    }

    public BufferedImage getOutputCache() {
        return outputCache;
    }

    @Override
    public boolean isConvertibleToSmartObject() {
        return false;
    }

    @Override
    public boolean checkInvariants() {
        if (!super.checkInvariants()) {
            return false;
        }

        if (!smartObject.containsSmartFilter(this)) {
            throw new AssertionError("smart object '%s' doesn't contain '%s'"
                .formatted(smartObject.getName(), getName()));
        }

        if (next != null) {
            if (next.getImageSource() != this) {
                throw new AssertionError("image source of " + next.getName() + " isn't " + getName());
            }
        }

        return true;
    }

    @Override
    public String getTypeString() {
        return "Smart Filter";
    }

    @Override
    public DebugNode createDebugNode(String key) {
        DebugNode node = super.createDebugNode(key);

        node.addString("imageSource class", imageSource.getClass().getSimpleName());
        node.add(imageSource.createDebugNode("imageSource"));
        node.addString("next", String.valueOf(next));
        node.addBoolean("cached", (outputCache != null));

        return node;
    }
}
