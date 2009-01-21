//
// $Id$

package com.threerings.tudey.server.logic;

import java.util.ArrayList;

import com.samskivert.util.ObserverList;

import com.threerings.config.ConfigReference;
import com.threerings.math.Rect;
import com.threerings.math.Vector2f;

import com.threerings.tudey.config.ActorConfig;
import com.threerings.tudey.config.HandlerConfig;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.server.TudeySceneManager;
import com.threerings.tudey.shape.Shape;
import com.threerings.tudey.shape.ShapeElement;

/**
 * Controls the state of an actor on the server.
 */
public class ActorLogic extends Logic
{
    /**
     * Initializes the actor.
     */
    public void init (
        TudeySceneManager scenemgr, ConfigReference<ActorConfig> ref,
        ActorConfig.Original config, int id, int timestamp, Vector2f translation, float rotation)
    {
        super.init(scenemgr);
        _config = config;
        _actor = createActor(ref, id, timestamp, translation, rotation);
        _actor.init(scenemgr.getConfigManager());
        _shape = new ShapeElement(config.shape);
        _shape.setUserObject(this);
        updateShape();

        // if specified, attempt to find a non-colliding spawn point
        if (config.adjustSpawnPoint && scenemgr.collides(this)) {
            adjustSpawnPoint();
        }
        _scenemgr.getActorSpace().add(_shape);

        // create the handlers
        ArrayList<HandlerLogic> handlers = new ArrayList<HandlerLogic>();
        for (HandlerConfig hconfig : config.handlers) {
            HandlerLogic handler = createHandler(hconfig, this);
            if (handler != null) {
                handlers.add(handler);
            }
        }
        _handlers = handlers.toArray(new HandlerLogic[handlers.size()]);

        // give subclasses a chance to set up
        didInit();
    }

    /**
     * Returns a reference to the actor object.
     */
    public Actor getActor ()
    {
        return _actor;
    }

    /**
     * Returns the current tick's snapshot of the actor.
     */
    public Actor getSnapshot ()
    {
        int timestamp = _scenemgr.getTimestamp();
        if (_snaptime < timestamp) {
            _snapshot = (Actor)_actor.clone();
            _snaptime = timestamp;
        }
        return _snapshot;
    }

    /**
     * Warps the actor.
     */
    public void warp (float x, float y, float rotation)
    {
        // set the warp flag and clear it on the next tick
        _actor.set(Actor.WARP);
        move(x, y, rotation);
        _scenemgr.addTickParticipant(new TudeySceneManager.TickParticipant() {
            public boolean tick (int timestamp) {
                _actor.clear(Actor.WARP);
                return false;
            }
        });
    }

    /**
     * Moves the actor and updates its shape.
     */
    public void move (float x, float y, float rotation)
    {
        _actor.getTranslation().set(x, y);
        _actor.setRotation(rotation);
        updateShape();
    }

    /**
     * Destroys the actor.
     */
    public void destroy (int timestamp)
    {
        // set the destroyed time and remove on the next tick
        _actor.setDestroyed(timestamp);
        _scenemgr.addTickParticipant(new TudeySceneManager.TickParticipant() {
            public boolean tick (int timestamp) {
                remove();
                return false;
            }
        });
    }

    @Override // documentation inherited
    public String[] getTags ()
    {
        return _config.tags;
    }

    @Override // documentation inherited
    public boolean isDefaultEntrance ()
    {
        return _config.defaultEntrance;
    }

    @Override // documentation inherited
    public Vector2f getTranslation ()
    {
        return _actor.getTranslation();
    }

    @Override // documentation inherited
    public float getRotation ()
    {
        return _actor.getRotation();
    }

    @Override // documentation inherited
    public Shape getShape ()
    {
        return _shape.getWorldShape();
    }

    @Override // documentation inherited
    public void addShapeObserver (ShapeObserver observer)
    {
        _shapeObservers.add(observer);
    }

    @Override // documentation inherited
    public void removeShapeObserver (ShapeObserver observer)
    {
        _shapeObservers.remove(observer);
    }

    @Override // documentation inherited
    public void signal (int timestamp, Logic source, String name)
    {
        for (HandlerLogic handler : _handlers) {
            handler.signal(timestamp, source, name);
        }
    }

    /**
     * Creates the actor object.
     */
    protected Actor createActor (
        ConfigReference<ActorConfig> ref, int id, int timestamp,
        Vector2f translation, float rotation)
    {
        return new Actor(ref, id, timestamp, translation, rotation);
    }

    /**
     * Adjusts the initial location of the actor so as to avoid collisions.
     */
    protected void adjustSpawnPoint ()
    {
        // get the bounds with respect to the position
        Rect bounds = _shape.getBounds();
        float width = bounds.getWidth() + 0.01f, height = bounds.getHeight() + 0.01f;
        Vector2f translation = _actor.getTranslation();
        float ox = translation.x, oy = translation.y;

        // test locations at increasing distances from the original
        for (int dist = 1; dist <= MAX_ADJUSTMENT_DISTANCE; dist++) {
            // loop counterclockwise around the center
            float bottom = oy - dist*height;
            for (int xx = -dist; xx <= +dist; xx++) {
                if (testSpawnPoint(ox, oy, ox + xx*width, bottom)) {
                    return;
                }
            }
            float right = ox + dist*width;
            for (int yy = 1 - dist, yymax = -yy; yy <= yymax; yy++) {
                if (testSpawnPoint(ox, oy, right, oy + yy*height)) {
                    return;
                }
            }
            float top = oy + dist*height;
            for (int xx = +dist; xx >= -dist; xx--) {
                if (testSpawnPoint(ox, oy, ox + xx*width, top)) {
                    return;
                }
            }
            float left = ox - dist*width;
            for (int yy = dist - 1, yymin = -yy; yy >= yymin; yy--) {
                if (testSpawnPoint(ox, oy, left, oy + yy*height)) {
                    return;
                }
            }
        }

        // if we exhaust our search, return to the original location
        _actor.getTranslation().set(ox, oy);
        updateShape();
    }

    /**
     * Tests the specified location as an adjusted spawn point.
     *
     * @return true if the spawn point is viable (in which case the actor's translation
     * will have been adjusted), false if not.
     */
    protected boolean testSpawnPoint (float ox, float oy, float nx, float ny)
    {
        // update the shape
        _actor.getTranslation().set(nx, ny);
        updateShape();

        // check for collision
        if (_scenemgr.collides(this)) {
            return false;
        }

        // make sure we can reach the original translation, ignoring actors
        return _scenemgr.getPathfinder().getEntryPath(
            this, MAX_ADJUSTMENT_PATH_LENGTH, ox, oy, false) != null;
    }

    /**
     * Updates the shape transform based on the actor's position.
     */
    protected void updateShape ()
    {
        _shape.getTransform().set(_actor.getTranslation(), _actor.getRotation(), 1f);
        _shape.updateBounds();

        // notify listeners
        _shapeObservers.apply(_shapeUpdatedOp);
    }

    /**
     * Removes this actor after it has been destroyed.
     */
    protected void remove ()
    {
        // remove from space and logic mapping
        _scenemgr.getActorSpace().remove(_shape);
        _scenemgr.removeActorLogic(_actor.getId());

        // notify the handlers
        for (HandlerLogic handler : _handlers) {
            handler.removed();
        }

        // give subclasses a chance to cleanup
        wasRemoved();
    }

    /**
     * Override to perform custom initialization.
     */
    protected void didInit ()
    {
        // nothing by default
    }

    /**
     * Override to perform custom cleanup.
     */
    protected void wasRemoved ()
    {
        // nothing by default
    }

    /** The actor configuration. */
    protected ActorConfig.Original _config;

    /** The actor object, which may be manipulated directly. */
    protected Actor _actor;

    /** The actor snapshot, which must not be modified once created. */
    protected Actor _snapshot;

    /** The timestamp of the actor snapshot. */
    protected int _snaptime;

    /** The actor's shape element. */
    protected ShapeElement _shape;

    /** The actor's event handlers. */
    protected HandlerLogic[] _handlers;

    /** The actor's shape observers. */
    protected ObserverList<ShapeObserver> _shapeObservers = ObserverList.newFastUnsafe();

    /** Used to notify observers of shape changes. */
    protected ObserverList.ObserverOp<ShapeObserver> _shapeUpdatedOp =
        new ObserverList.ObserverOp<ShapeObserver>() {
        public boolean apply (ShapeObserver observer) {
            observer.shapeUpdated(ActorLogic.this);
            return true;
        }
    };

    /** The maximum number of steps away from the spawn point for adjustment. */
    protected static final int MAX_ADJUSTMENT_DISTANCE = 4;

    /** The maximum path length from the origin for adjustment. */
    protected static final float MAX_ADJUSTMENT_PATH_LENGTH = 8f;
}
