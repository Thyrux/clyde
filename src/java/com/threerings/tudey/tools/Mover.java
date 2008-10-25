//
// $Id$

package com.threerings.tudey.tools;

import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

import com.threerings.math.FloatMath;
import com.threerings.math.Rect;
import com.threerings.math.Transform3D;
import com.threerings.math.Vector2f;
import com.threerings.math.Vector3f;

import com.threerings.tudey.client.cursor.SelectionCursor;
import com.threerings.tudey.data.TudeySceneModel.Entry;
import com.threerings.tudey.data.TudeySceneModel.TileEntry;
import com.threerings.tudey.util.TudeySceneMetrics;

/**
 * The mover tool.
 */
public class Mover extends EditorTool
{
    /**
     * Creates the mover tool.
     */
    public Mover (SceneEditor editor)
    {
        super(editor);
    }

    /**
     * Requests to start moving the specified entries.
     */
    public void move (Entry... entries)
    {
        // make sure some entries exist
        _entries = new Entry[entries.length];
        if (entries.length == 0) {
            return;
        }

        // clone the entries, find the bounds, and see if any are tiles
        Rect bounds = new Rect(), ebounds = new Rect();
        int minElevation = Integer.MAX_VALUE, maxElevation = Integer.MIN_VALUE;
        _tiles = false;
        for (int ii = 0; ii < entries.length; ii++) {
            Entry entry = _entries[ii] = (Entry)entries[ii].clone();
            _tiles |= (entry instanceof TileEntry);
            entry.getBounds(_editor.getConfigManager(), ebounds);
            bounds.addLocal(ebounds);
            int elevation = entry.getElevation();
            if (elevation != Integer.MIN_VALUE) {
                minElevation = Math.min(minElevation, elevation);
                maxElevation = Math.max(maxElevation, elevation);
            }
        }
        // find the center and elevation
        bounds.getCenter(_center);
        _elevation = (minElevation < maxElevation) ? (minElevation + maxElevation)/2 : 0;

        // reset the angle
        _angle = 0f;
    }

    @Override // documentation inherited
    public void init ()
    {
        _cursor = new SelectionCursor(_editor, _editor.getView());
    }

    @Override // documentation inherited
    public void deactivate ()
    {
        // cancel any movement in process
        super.deactivate();
        move(new Entry[0]);
    }

    @Override // documentation inherited
    public void tick (float elapsed)
    {
        updateCursor();
        if (_cursorVisible) {
            _cursor.tick(elapsed);
        }
    }

    @Override // documentation inherited
    public void enqueue ()
    {
        if (_cursorVisible) {
            _cursor.enqueue();
        }
    }

    @Override // documentation inherited
    public void mousePressed (MouseEvent event)
    {
        if (event.getButton() == MouseEvent.BUTTON1 && _cursorVisible) {
            // place the transformed entries and clear the tool
            for (Entry entry : _tentries) {
                _editor.overwriteEntry((Entry)entry.clone());
            }
            move(new Entry[0]);
        }
    }

    @Override // documentation inherited
    public void mouseWheelMoved (MouseWheelEvent event)
    {
        // adjust in terms of coarse (ninety degree) or fine increments
        if (_cursorVisible) {
            float increment = (_tiles || !event.isShiftDown()) ?
                FloatMath.HALF_PI : FINE_ROTATION_INCREMENT;
            _angle = (Math.round(_angle / increment) + event.getWheelRotation()) * increment;
        }
    }

    /**
     * Updates the entry transform and cursor visibility based on the location of the mouse cursor.
     */
    protected void updateCursor ()
    {
        if (!(_cursorVisible = (_entries.length > 0) && getMousePlaneIntersection(_isect) &&
                !_editor.isControlDown())) {
            return;
        }
        Vector2f rcenter = _center.rotate(_angle);
        _isect.x -= rcenter.x;
        _isect.y -= rcenter.y;
        if (_tiles || !_editor.isShiftDown()) {
            _isect.x = Math.round(_isect.x);
            _isect.y = Math.round(_isect.y);
        }
        _transform.getTranslation().set(_isect.x, _isect.y,
            TudeySceneMetrics.getTileZ(_editor.getGrid().getElevation() - _elevation));
        _transform.getRotation().fromAngleAxis(_angle, Vector3f.UNIT_Z);

        // transform the entries and update the cursor
        _cursor.update(_tentries = transform(_entries, _transform));
    }

    /**
     * Transforms the supplied entries, returning a new entry array containing the results.
     */
    protected Entry[] transform (Entry[] entries, Transform3D transform)
    {
        Entry[] tentries = new Entry[entries.length];
        for (int ii = 0; ii < entries.length; ii++) {
            Entry tentry = tentries[ii] = (Entry)entries[ii].clone();
            tentry.transform(_editor.getConfigManager(), transform);
        }
        return tentries;
    }

    /** The cursor representing the selection that we're moving. */
    protected SelectionCursor _cursor;

    /** The (untransformed) entries that we're moving. */
    protected Entry[] _entries = new Entry[0];

    /** The transformed entries. */
    protected Entry[] _tentries;

    /** Whether or not any of the entries are tiles (in which case we must stay aligned). */
    protected boolean _tiles;

    /** The center of the entries. */
    protected Vector2f _center = new Vector2f();

    /** The entries' elevation. */
    protected int _elevation;

    /** Whether or not the cursor is in the window. */
    protected boolean _cursorVisible;

    /** The angle about the z axis. */
    protected float _angle;

    /** The selection transform. */
    protected Transform3D _transform = new Transform3D(Transform3D.RIGID);

    /** Holds the result of an intersection test. */
    protected Vector3f _isect = new Vector3f();
}
