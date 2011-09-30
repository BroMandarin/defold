package com.dynamo.cr.tileeditor.core;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.vecmath.Point2f;
import javax.vecmath.Vector2f;

import org.eclipse.core.commands.operations.IOperationHistory;
import org.eclipse.core.commands.operations.IOperationHistoryListener;
import org.eclipse.core.commands.operations.IUndoContext;
import org.eclipse.core.commands.operations.OperationHistoryEvent;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;

import com.dynamo.cr.tileeditor.core.Layer.Cell;
import com.dynamo.cr.tileeditor.operations.SetCellsOperation;

public class GridPresenter implements IGridView.Presenter, PropertyChangeListener, IOperationHistoryListener {

    public static final float ZOOM_FACTOR = 0.005f;

    private static class SelectedTile {
        public final int tileIndex;
        public final boolean hFlip;
        public final boolean vFlip;

        public SelectedTile() {
            this.tileIndex = -1;
            this.hFlip = false;
            this.vFlip = false;
        }

        public SelectedTile(int tileIndex, boolean hFlip, boolean vFlip) {
            this.tileIndex = tileIndex;
            this.hFlip = hFlip;
            this.vFlip = vFlip;
        }

    }

    @Inject private IOperationHistory undoHistory;
    @Inject private IUndoContext undoContext;

    private final GridModel model;
    private final IGridView view;

    private boolean loading = false;
    private int undoRedoCounter = 0;
    private SelectedTile selectedTile;
    private Map<Long, Layer.Cell> oldCells;
    private final Point2f previewPosition;
    private float previewZoom;

    @Inject
    public GridPresenter(GridModel model, IGridView view, ILogger logger) {
        this.model = model;
        this.view = view;

        this.model.addTaggedPropertyListener(this);
        this.selectedTile = new SelectedTile();

        this.previewPosition = new Point2f(0.0f, 0.0f);
        this.previewZoom = 1.0f;
    }

    @PreDestroy
    public void dispose() {
        undoHistory.removeOperationHistoryListener(this);
    }

    @Inject
    public void init() {
        undoHistory.addOperationHistoryListener(this);
    }

    public void refresh() {
        this.view.setLayers(this.model.getLayers());
        this.view.refreshProperties();
        boolean validModel = this.model.isValid();
        this.view.setValidModel(validModel);
        this.view.setPreview(new Point2f(0.0f, 0.0f), 1.0f);
        TileSetModel tileSetModel = this.model.getTileSetModel();
        if (tileSetModel != null) {
            this.view.setTileSet(tileSetModel.getLoadedImage(),
                    tileSetModel.getTileWidth(),
                    tileSetModel.getTileHeight(),
                    tileSetModel.getTileMargin(),
                    tileSetModel.getTileSpacing());
        }
    }

    private void setUndoRedoCounter(int undoRedoCounter) {
        boolean prevDirty = this.undoRedoCounter != 0;
        boolean dirty = undoRedoCounter != 0;
        // NOTE: We must set undoRedoCounter before we call setDirty.
        // The "framework" might as for isDirty()
        this.undoRedoCounter = undoRedoCounter;
        if (prevDirty != dirty) {
            this.view.setDirty(dirty);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (this.loading)
            return;


        boolean validModel = this.model.isValid();
        this.view.setValidModel(validModel);
        this.view.refreshProperties();
        if (evt.getNewValue() instanceof IStatus) {
        } else {
            Object source = evt.getSource();
            String propName = evt.getPropertyName();
            if (source instanceof GridModel) {
                if (propName.equals("tileSet")) {
                    TileSetModel tileSetModel = this.model.getTileSetModel();
                    if (tileSetModel != null && tileSetModel.isValid()) {
                        this.view.setTileSet(tileSetModel.getLoadedImage(),
                                tileSetModel.getTileWidth(),
                                tileSetModel.getTileHeight(),
                                tileSetModel.getTileMargin(),
                                tileSetModel.getTileSpacing());
                    }
                } else if (propName.equals("layers")) {
                    this.view.setLayers((List<Layer>)evt.getNewValue());
                }
            } else if (source instanceof Layer) {
                if (propName.equals("cells")) {
                    int layerIndex = this.model.getLayers().indexOf(source);
                    this.view.setCells(layerIndex, (Map<Long, Cell>)evt.getNewValue());
                }
            }
        }
    }

    @Override
    public void onSelectTile(int tileIndex, boolean hFlip, boolean vFlip) {
        int selectedTile = tileIndex;
        if (this.selectedTile != null && this.selectedTile.tileIndex == tileIndex) {
            selectedTile = -1;
        }
        this.selectedTile = new SelectedTile(selectedTile, hFlip, vFlip);
        this.view.setSelectedTile(selectedTile, hFlip, vFlip);
    }

    @Override
    public void onPaintBegin() {
        this.oldCells = new HashMap<Long, Layer.Cell>();
    }

    @Override
    public void onPaint(int x, int y) {
        if (this.oldCells != null) {
            long cellIndex = Layer.toCellIndex(x, y);
            Cell cell = null;
            if (this.selectedTile.tileIndex >= 0) {
                cell = new Cell(this.selectedTile.tileIndex, this.selectedTile.hFlip, this.selectedTile.vFlip);
            }
            Cell oldCell = this.model.getCell(cellIndex);
            if ((cell == null && oldCell != null) || (cell != null && !cell.equals(oldCell))) {
                this.model.setCell(cellIndex, cell);
                cell = this.model.getCell(cellIndex);
                this.view.setCell(this.model.getSelectedLayer(), cellIndex, cell);
                this.oldCells.put(cellIndex, oldCell);
            }
        }
    }

    @Override
    public void onPaintEnd() {
        if (this.oldCells != null && !this.oldCells.isEmpty()) {
            this.model.executeOperation(new SetCellsOperation(this.model, this.oldCells));
            this.oldCells = null;
        }
    }

    @Override
    public void onLoad(InputStream is) {
        this.loading = true;
        this.model.load(is);
        this.loading = false;
        setUndoRedoCounter(0);
        refresh();
    }

    @Override
    public void onSave(OutputStream os, IProgressMonitor monitor) throws IOException {
        this.model.save(os, monitor);
        setUndoRedoCounter(0);
    }

    @Override
    public void onPreviewPan(int dx, int dy) {
        Vector2f delta = new Vector2f(dx, -dy);
        delta.scale(1.0f / this.previewZoom);
        this.previewPosition.add(delta);
        this.view.setPreview(this.previewPosition, this.previewZoom);
    }

    @Override
    public void onPreviewZoom(int delta) {
        float dz = -delta * ZOOM_FACTOR;
        this.previewZoom += (this.previewZoom > 1.0f) ? dz * this.previewZoom : dz;
        this.previewZoom = Math.max(0.1f, this.previewZoom);
        this.view.setPreview(this.previewPosition, this.previewZoom);
    }

    @Override
    public void onRefresh() {
        refresh();
    }

    @Override
    public boolean isDirty() {
        return undoRedoCounter != 0;
    }

    @Override
    public void historyNotification(OperationHistoryEvent event) {
        if (!event.getOperation().hasContext(this.undoContext)) {
            // Only handle operations related to this editor
            return;
        }
        int type = event.getEventType();
        switch (type) {
        case OperationHistoryEvent.DONE:
        case OperationHistoryEvent.REDONE:
            setUndoRedoCounter(undoRedoCounter + 1);
            break;
        case OperationHistoryEvent.UNDONE:
            setUndoRedoCounter(undoRedoCounter - 1);
            break;
        }
    }
}
