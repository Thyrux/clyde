//
// $Id$
//
// Clyde library - tools for developing networked games
// Copyright (C) 2005-2010 Three Rings Design, Inc.
//
// Redistribution and use in source and binary forms, with or without modification, are permitted
// provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this list of
//    conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice, this list of
//    conditions and the following disclaimer in the documentation and/or other materials provided
//    with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
// PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
// INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
// TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package com.threerings.tudey.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import com.google.inject.Inject;
import com.google.inject.Injector;

import com.samskivert.util.HashIntMap;
import com.samskivert.util.IntMap;
import com.samskivert.util.IntMaps;
import com.samskivert.util.ObserverList;
import com.samskivert.util.Queue;
import com.samskivert.util.RandomUtil;
import com.samskivert.util.RunAnywhere;
import com.samskivert.util.RunQueue;

import com.threerings.presents.data.ClientObject;
import com.threerings.presents.server.ClientManager;
import com.threerings.presents.server.PresentsSession;

import com.threerings.crowd.data.BodyObject;
import com.threerings.crowd.data.OccupantInfo;
import com.threerings.crowd.data.PlaceObject;
import com.threerings.crowd.server.CrowdSession;

import com.threerings.whirled.server.SceneManager;

import com.threerings.config.ConfigManager;
import com.threerings.config.ConfigReference;
import com.threerings.math.Rect;
import com.threerings.math.SphereCoords;
import com.threerings.math.Transform2D;
import com.threerings.math.Vector2f;

import com.threerings.tudey.config.ActorConfig;
import com.threerings.tudey.config.CameraConfig;
import com.threerings.tudey.config.EffectConfig;
import com.threerings.tudey.data.EntityKey;
import com.threerings.tudey.data.InputFrame;
import com.threerings.tudey.data.TudeyBodyObject;
import com.threerings.tudey.data.TudeyCodes;
import com.threerings.tudey.data.TudeySceneConfig;
import com.threerings.tudey.data.TudeySceneModel;
import com.threerings.tudey.data.TudeySceneModel.Entry;
import com.threerings.tudey.data.TudeySceneObject;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.data.effect.Effect;
import com.threerings.tudey.dobj.ActorDelta;
import com.threerings.tudey.server.logic.ActorLogic;
import com.threerings.tudey.server.logic.EffectLogic;
import com.threerings.tudey.server.logic.EntryLogic;
import com.threerings.tudey.server.logic.Logic;
import com.threerings.tudey.server.logic.PawnLogic;
import com.threerings.tudey.server.util.Pathfinder;
import com.threerings.tudey.server.util.SceneTicker;
import com.threerings.tudey.shape.Shape;
import com.threerings.tudey.shape.ShapeElement;
import com.threerings.tudey.space.HashSpace;
import com.threerings.tudey.space.SpaceElement;
import com.threerings.tudey.util.ActorAdvancer;
import com.threerings.tudey.util.TudeySceneMetrics;
import com.threerings.tudey.util.TudeyUtil;

import static com.threerings.tudey.Log.*;

/**
 * Manager for Tudey scenes.
 */
public class TudeySceneManager extends SceneManager
    implements TudeySceneProvider, TudeySceneModel.Observer,
        ActorAdvancer.Environment, RunQueue, TudeyCodes
{
    /**
     * An interface for objects that take part in the server tick.
     */
    public interface TickParticipant
    {
        /**
         * Ticks the participant.
         *
         * @param timestamp the timestamp of the current tick.
         * @return true to continue ticking the participant, false to remove it from the list.
         */
        public boolean tick (int timestamp);
    }

    /**
     * An interface for objects to notify when actors are added or removed.
     */
    public interface ActorObserver
    {
        /**
         * Notes that an actor has been added.
         */
        public void actorAdded (ActorLogic logic);

        /**
         * Notes that an actor has been removed.
         */
        public void actorRemoved (ActorLogic logic);
    }

    /**
     * Base interface for sensors.
     */
    public interface Sensor
    {
        /**
         * Returns the sensor's bitmask.  Only triggers whose flags intersect the mask will
         * activate the sensor.
         */
        public int getMask ();

        /**
         * Triggers the sensor.
         *
         * @param timestamp the timestamp of the intersection.
         * @param actor the logic object of the actor that triggered the sensor.
         */
        public void trigger (int timestamp, ActorLogic actor);
    }

    /**
     * An interface for objects that should be notified when actors intersect them.
     */
    public interface IntersectionSensor extends Sensor
    {
    }

    /**
     * Returns the delay with which the clients display information received from the server in
     * order to compensate for network jitter and dropped packets.
     */
    public int getBufferDelay ()
    {
        return TudeyUtil.getBufferDelay(getTickInterval());
    }

    /**
     * Returns the number of ticks per second.
     */
    public int getTicksPerSecond ()
    {
        return 1000 / getTickInterval();
    }

    /**
     * Returns the interval at which we call the {@link #tick} method.
     */
    public int getTickInterval ()
    {
        return (_ticker == null) ? DEFAULT_TICK_INTERVAL : _ticker.getActualInterval();
    }

    /**
     * Returns the interval at which clients transmit their input frames.
     */
    public int getTransmitInterval ()
    {
        return ((TudeySceneConfig)_config).getTransmitInterval();
    }

    /**
     * Returns a reference to the configuration manager for the scene.
     */
    public ConfigManager getConfigManager ()
    {
        return _cfgmgr;
    }

    /**
     * Adds a participant to notify at each tick.
     */
    public void addTickParticipant (TickParticipant participant)
    {
        _tickParticipants.add(participant);
    }

    /**
     * Removes a participant from the tick list.
     */
    public void removeTickParticipant (TickParticipant participant)
    {
        _tickParticipants.remove(participant);
    }

    /**
     * Adds an observer for actor events.
     */
    public void addActorObserver (ActorObserver observer)
    {
        _actorObservers.add(observer);
    }

    /**
     * Removes an actor observer.
     */
    public void removeActorObserver (ActorObserver observer)
    {
        _actorObservers.remove(observer);
    }

    /**
     * Returns the timestamp of the current tick.
     */
    public int getTimestamp ()
    {
        return _timestamp;
    }

    /**
     * Returns the timestamp of the last tick.
     */
    public int getPreviousTimestamp ()
    {
        return _previousTimestamp;
    }

    /**
     * Returns the approximate timestamp of the next tick.
     */
    public int getNextTimestamp ()
    {
        return _timestamp + getTickInterval();
    }

    /**
     * Returns the amount of time spent processing the last tick.
     */
    public long getTickDuration ()
    {
        return _tickDuration;
    }

    /**
     * Returns the list of logic objects with the supplied tag, or <code>null</code> for none.
     */
    public ArrayList<Logic> getTagged (String tag)
    {
        return _tagged.get(tag);
    }

    /**
     * Returns the list of logic objects that are instances of the supplied class, or
     * <code>null</code> for none.
     */
    public ArrayList<Logic> getInstances (Class<? extends Logic> clazz)
    {
        return _instances.get(clazz);
    }

    /**
     * Returns a reference to the actor space.
     */
    public HashSpace getActorSpace ()
    {
        return _actorSpace;
    }

    /**
     * Returns a reference to the sensor space.
     */
    public HashSpace getSensorSpace ()
    {
        return _sensorSpace;
    }

    /**
     * Returns a reference to the pathfinder object.
     */
    public Pathfinder getPathfinder ()
    {
        return _pathfinder;
    }

    /**
     * Sets the default untransformed area of interest region for clients.
     */
    public void setDefaultLocalInterest (Rect interest)
    {
        _defaultLocalInterest = interest;
    }

    /**
     * Returns the default untransformed area of interest region for clients.
     */
    public Rect getDefaultLocalInterest ()
    {
        return _defaultLocalInterest;
    }

    /**
     * Checks whether we should show region debug effects.
     */
    public boolean getDebugRegions ()
    {
        return false;
    }

    /**
     * Spawns an actor with the named configuration.
     */
    public ActorLogic spawnActor (
        int timestamp, Vector2f translation, float rotation, String name)
    {
        return spawnActor(timestamp, translation, rotation,
            new ConfigReference<ActorConfig>(name));
    }

    /**
     * Spawns an actor with the supplied name and arguments.
     */
    public ActorLogic spawnActor (
        int timestamp, Vector2f translation, float rotation, String name,
        String firstKey, Object firstValue, Object... otherArgs)
    {
        return spawnActor(timestamp, translation, rotation,
            new ConfigReference<ActorConfig>(name, firstKey, firstValue, otherArgs));
    }

    /**
     * Spawns an actor with the referenced configuration.
     */
    public ActorLogic spawnActor (
        int timestamp, Vector2f translation, float rotation, ConfigReference<ActorConfig> ref)
    {
        // return immediately if the place has shut down
        if (!_plobj.isActive()) {
            return null;
        }

        // attempt to resolve the implementation
        ActorConfig config = _cfgmgr.getConfig(ActorConfig.class, ref);
        ActorConfig.Original original = (config == null) ? null : config.getOriginal(_cfgmgr);
        if (original == null) {
            log.warning("Failed to resolve actor config.", "actor", ref, "where", where());
            return null;
        }

        // create the logic object
        ActorLogic logic = (ActorLogic)createLogic(original.getLogicClassName());
        if (logic == null) {
            return null;
        }

        // initialize the logic and add it to the map
        int id = ++_lastActorId;
        logic.init(this, ref, original, id, timestamp, translation, rotation);
        _actors.put(id, logic);
        addMappings(logic);

        // special processing for static actors
        if (logic.isStatic()) {
            _staticActors.add(logic);
            _staticActorsAdded.add(logic);
        }

        // notify observers
        _actorObservers.apply(_actorAddedOp.init(logic));

        return logic;
    }

    /**
     * Fires off an effect at the with the named configuration.
     */
    public EffectLogic fireEffect (
        int timestamp, Logic target, Vector2f translation, float rotation, String name)
    {
        return fireEffect(timestamp, target, translation, rotation,
            new ConfigReference<EffectConfig>(name));
    }

    /**
     * Fires off an effect with the supplied name and arguments.
     */
    public EffectLogic fireEffect (
        int timestamp, Logic target, Vector2f translation, float rotation, String name,
        String firstKey, Object firstValue, Object... otherArgs)
    {
        return fireEffect(timestamp, target, translation, rotation,
            new ConfigReference<EffectConfig>(name, firstKey, firstValue, otherArgs));
    }

    /**
     * Fires off an effect with the referenced configuration.
     */
    public EffectLogic fireEffect (
        int timestamp, Logic target, Vector2f translation, float rotation,
        ConfigReference<EffectConfig> ref)
    {
        // return immediately if the place has shut down
        if (!_plobj.isActive()) {
            return null;
        }

        // attempt to resolve the implementation
        EffectConfig config = _cfgmgr.getConfig(EffectConfig.class, ref);
        EffectConfig.Original original = (config == null) ? null : config.getOriginal(_cfgmgr);
        if (original == null) {
            log.warning("Failed to resolve effect config.", "effect", ref, "where", where());
            return null;
        }

        // create the logic class
        EffectLogic logic = (EffectLogic)createLogic(original.getLogicClassName());
        if (logic == null) {
            return null;
        }

        // initialize the logic and add it to the list
        logic.init(this, ref, original, timestamp, target, translation, rotation);
        _effectsFired.add(logic);

        return logic;
    }

    /**
     * Creates an instance of the logic object with the specified class name using the injector,
     * logging a warning and returning <code>null</code> on error.
     */
    public Logic createLogic (String cname)
    {
        try {
            return (Logic)_injector.getInstance(Class.forName(cname));
        } catch (Exception e) {
            log.warning("Failed to instantiate logic.", "class", cname, e);
            return null;
        }
    }

    /**
     * Returns the logic object for the entity with the provided key, if any.
     */
    public Logic getLogic (EntityKey key)
    {
        if (key instanceof EntityKey.Entry) {
            return getEntryLogic(((EntityKey.Entry)key).getKey());
        } else if (key instanceof EntityKey.Actor) {
            return getActorLogic(((EntityKey.Actor)key).getId());
        } else {
            return null;
        }
    }

    /**
     * Returns the logic object for the entry with the provided key, if any.
     */
    public EntryLogic getEntryLogic (Object key)
    {
        return _entries.get(key);
    }

    /**
     * Returns the logic object for the actor with the provided id, if any.
     */
    public ActorLogic getActorLogic (int id)
    {
        return _actors.get(id);
    }

    /**
     * Populates the supplied collection with references to all non-static actors visible to the
     * specified target whose influence regions intersect the provided bounds.
     */
    public void getVisibleActors (PawnLogic target, Rect bounds, Collection<ActorLogic> results)
    {
        _actorSpace.getElements(bounds, _elements);
        for (int ii = 0, nn = _elements.size(); ii < nn; ii++) {
            ActorLogic actor = (ActorLogic)_elements.get(ii).getUserObject();
            if (!actor.isStatic() && (target == null || actor.isVisible(target))) {
                results.add(actor);
            }
        }
        _elements.clear();
    }

    /**
     * Returns a reference to the set of static actors.
     */
    public Set<ActorLogic> getStaticActors ()
    {
        return _staticActors;
    }

    /**
     * Returns a reference to the set of static actors added on the current tick.
     */
    public Set<ActorLogic> getStaticActorsAdded ()
    {
        return _staticActorsAdded;
    }

    /**
     * Returns a reference to the set of static actors updated on the current tick.
     */
    public Set<ActorLogic> getStaticActorsUpdated ()
    {
        return _staticActorsUpdated;
    }

    /**
     * Returns a reference to the set of static actors removed on the current tick.
     */
    public Set<ActorLogic> getStaticActorsRemoved ()
    {
        return _staticActorsRemoved;
    }

    /**
     * Returns an array containing all effects fired on the current tick whose influence regions
     * intersect the provided bounds.
     */
    public Effect[] getEffectsFired (Rect bounds)
    {
        for (int ii = 0, nn = _effectsFired.size(); ii < nn; ii++) {
            EffectLogic logic = _effectsFired.get(ii);
            if (logic.getShape().getBounds().intersects(bounds)) {
                _effects.add(logic.getEffect());
            }
        }
        Effect[] array = _effects.toArray(new Effect[_effects.size()]);
        _effects.clear();
        return array;
    }

    /**
     * Removes the logic mapping for the actor with the given id.
     */
    public void removeActorLogic (int id)
    {
        ActorLogic logic = _actors.remove(id);
        if (logic == null) {
            log.warning("Missing actor to remove.", "where", where(), "id", id);
            return;
        }
        // remove mappings
        removeMappings(logic);

        // special handling for static actors
        if (logic.isStatic()) {
            _staticActors.remove(logic);
            if (!_staticActorsAdded.remove(logic)) {
                _staticActorsUpdated.remove(logic);
                _staticActorsRemoved.add(logic);
            }
        }

        // notify observers
        _actorObservers.apply(_actorRemovedOp.init(logic));
    }

    /**
     * Triggers any intersection sensors intersecting the specified shape.
     */
    public int triggerIntersectionSensors (int timestamp, ActorLogic actor)
    {
        return triggerSensors(
            IntersectionSensor.class, timestamp, actor.getShape(),
            actor.getActor().getCollisionFlags(), actor);
    }

    /**
     * Triggers any sensors of the specified type intersecting the specified shape.
     */
    public int triggerSensors (
        Class<? extends Sensor> type, int timestamp, Shape shape, int flags, ActorLogic actor)
    {

        return triggerSensors(type, timestamp, ImmutableList.of(shape), flags, actor);
    }

    /**
     * Triggers any sensors of the specified type intersecting the specified shape.
     */
    public int triggerSensors (Class<? extends Sensor> type, int timestamp,
            Collection<Shape> shapes, int flags, ActorLogic actor)
    {
        Set<SpaceElement> elements = Sets.newHashSet();
        for (Shape shape : shapes) {
            _sensorSpace.getIntersecting(shape, elements);
        }
        int count = 0;
        for (SpaceElement element : elements) {
            Sensor sensor = (Sensor)element.getUserObject();
            if (type.isInstance(sensor) && (flags & sensor.getMask()) != 0) {
                sensor.trigger(timestamp, actor);
                count++;
            }
        }
        return count;
    }

    /**
     * Determines whether the specified actor collides with anything in the environment.
     */
    public boolean collides (ActorLogic logic)
    {
        return collides(logic, logic.getShape());
    }

    /**
     * Determines whether the specified actor collides with anything in the environment.
     */
    public boolean collides (ActorLogic logic, Shape shape)
    {
        return collides(logic, shape, _timestamp);
    }

    /**
     * Determines whether the specified actor collides with anything in the environment.
     */
    public boolean collides (ActorLogic logic, Shape shape, int timestamp)
    {
        return collides(logic.getActor(), shape, timestamp);
    }

    /**
     * Determines whether the specified actor collides with anything in the environment.
     */
    public boolean collides (Actor actor, Shape shape, int timestamp)
    {
        // check the scene model
        if (((TudeySceneModel)_scene.getSceneModel()).collides(actor, shape)) {
            return true;
        }

        // look for intersecting elements
        _actorSpace.getIntersecting(shape, _elements);
        try {
            for (int ii = 0, nn = _elements.size(); ii < nn; ii++) {
                SpaceElement element = _elements.get(ii);
                Actor oactor = ((ActorLogic)element.getUserObject()).getActor();
                if (timestamp < oactor.getDestroyed() && actor.canCollide(oactor)) {
                    return true;
                }
            }
        } finally {
            _elements.clear();
        }
        return false;
    }

    /**
     * Determines whether the specified shape collides with anything in the environment.
     */
    public boolean collides (int mask, Shape shape)
    {
        return collides(mask, shape, _timestamp);
    }

    /**
     * Determines whether the specified shape collides with anything in the environment.
     */
    public boolean collides (int mask, Shape shape, int timestamp)
    {
        // check the scene model
        if (((TudeySceneModel)_scene.getSceneModel()).collides(mask, shape)) {
            return true;
        }

        // look for intersecting elements
        _actorSpace.getIntersecting(shape, _elements);
        try {
            for (int ii = 0, nn = _elements.size(); ii < nn; ii++) {
                SpaceElement element = _elements.get(ii);
                Actor actor = ((ActorLogic)element.getUserObject()).getActor();
                if (timestamp < actor.getDestroyed() && (actor.getCollisionFlags() & mask) != 0) {
                    return true;
                }
            }
        } finally {
            _elements.clear();
        }
        return false;
    }

    /**
     * Notes that a static actor's state has changed.
     */
    public void staticActorUpdated (ActorLogic logic)
    {
        int id = logic.getActor().getId();
        if (!_staticActorsAdded.contains(logic)) {
            _staticActorsUpdated.add(logic);
        }
    }

    /**
     * Notes that a body will be entering via the identified portal.
     */
    public void mapEnteringBody (BodyObject body, Object portalKey)
    {
        _entering.put(body.getOid(), portalKey);
    }

    /**
     * Clears out the mapping for an entering body.
     */
    public void clearEnteringBody (BodyObject body)
    {
        _entering.remove(body.getOid());
    }

    @Override // from PlaceManager
    public void bodyWillEnter (BodyObject body)
    {
        // configure the client's message throttle to 1.5 times the absolute minimum
        PresentsSession client = _clmgr.getClient(body.username);
        if (client != null) {
            client.setIncomingMessageThrottle(1500 / getTransmitInterval());
        }

        // add the pawn and configure a local to provide its id
        ConfigReference<ActorConfig> ref = getPawnConfig(body);
        if (ref != null) {
            Vector2f translation = Vector2f.ZERO;
            float rotation = 0f;
            Object portalKey = _entering.remove(body.getOid());
            if (portalKey instanceof Logic) {
                // make sure the logic is still active
                Logic entrance = (Logic)portalKey;
                if (entrance.isActive()) {
                    translation = entrance.getTranslation();
                    rotation = entrance.getRotation();
                }
            } else if (portalKey instanceof String) {
                List<Logic> tagged = getTagged((String)portalKey);
                if (tagged != null) {
                    Logic entrance = RandomUtil.pickRandom(tagged);
                    translation = entrance.getTranslation();
                    rotation = entrance.getRotation();
                }
            } else if (portalKey instanceof Transform2D) {
                Transform2D transform = (Transform2D)portalKey;
                translation = transform.extractTranslation();
                rotation = transform.extractRotation();

            } else if (portalKey != null) {
                // get the translation/rotation from the entering portal
                Entry entry = ((TudeySceneModel)_scene.getSceneModel()).getEntry(portalKey);
                if (entry != null) {
                    translation = entry.getTranslation(_cfgmgr);
                    rotation = entry.getRotation(_cfgmgr);
                }
            }
            if (translation == Vector2f.ZERO) {
                // select a default entrance
                Logic entrance = getDefaultEntrance();
                if (entrance != null) {
                    translation = entrance.getTranslation();
                    rotation = entrance.getRotation();
                }
            }
            final ActorLogic logic = spawnActor(getNextTimestamp(), translation, rotation, ref);
            if (logic != null) {
                logic.bodyWillEnter(body);
                ((TudeyBodyObject)body).setPawnId(logic.getActor().getId());
            }
        }

        // now let the body actually enter the scene
        super.bodyWillEnter(body);
    }

    @Override // from PlaceManager
    public void bodyWillLeave (BodyObject body)
    {
        super.bodyWillLeave(body);
        TudeyBodyObject tbody = (TudeyBodyObject)body;
        if (tbody.pawnId != 0) {
            ActorLogic logic = _actors.get(tbody.pawnId);
            if (logic != null) {
                logic.bodyWillLeave(body);
            } else {
                log.warning("Missing pawn for leaving body.", "pawnId", tbody.pawnId,
                    "who", tbody.who(), "where", where());
            }
            tbody.setPawnId(0);
        }
    }

    // documentation inherited from interface TudeySceneProvider
    public void enteredPlace (ClientObject caller)
    {
        // forward to client liaison
        ClientLiaison client = _clients.get(caller.getOid());
        if (client == null) {
            log.warning("Received entrance notification from unknown client.",
                "who", caller.who(), "where", where());
            return;
        }
        client.enteredPlace();
    }

    // documentation inherited from interface TudeySceneProvider
    public void enqueueInputReliable (
        ClientObject caller, int acknowledge, int smoothedTime, InputFrame[] frames)
    {
        // these are handled in exactly the same way; the methods are separate to provide different
        // transport options
        enqueueInputUnreliable(caller, acknowledge, smoothedTime, frames);
    }

    // documentation inherited from interface TudeySceneProvider
    public void enqueueInputUnreliable (
        ClientObject caller, int acknowledge, int smoothedTime, InputFrame[] frames)
    {
        // forward to client liaison
        ClientLiaison client = _clients.get(caller.getOid());
        if (client != null) {
            // ping is current time minus client's smoothed time estimate
            int currentTime = _timestamp + (int)(RunAnywhere.currentTimeMillis() - _lastTick);
            client.enqueueInput(acknowledge, currentTime - smoothedTime, frames);
        } else {
            // this doesn't require a warning; it's probably an out-of-date packet from a client
            // that has just left the scene
            log.debug("Received input from unknown client.",
                "who", caller.who(), "where", where());
        }
    }

    // documentation inherited from interface TudeySceneProvider
    public void setTarget (ClientObject caller, int pawnId)
    {
        // get the client liaison
        int cloid = caller.getOid();
        ClientLiaison client = _clients.get(cloid);
        if (client == null) {
            log.warning("Received target request from unknown client.",
                "who", caller.who(), "where", where());
            return;
        }

        // make sure they're not controlling a pawn of their own
        //if (_tsobj.getPawnId(cloid) > 0) {
        //    log.warning("User with pawn tried to set target.",
        //        "who", caller.who(), "pawnId", pawnId);
        //    return;
        //}

        // retrieve the actor and ensure it's a pawn
        ActorLogic target = _actors.get(pawnId);
        if (target instanceof PawnLogic) {
            client.setTarget((PawnLogic)target);
        } else {
            log.warning("User tried to target non-pawn.", "who",
                caller.who(), "actor", (target == null) ? null : target.getActor());
        }
    }

    // documentation inherited from interface TudeySceneProvider
    public void setCameraParams (ClientObject caller, CameraConfig config, float aspect)
    {
        // forward to client liaison
        ClientLiaison client = _clients.get(caller.getOid());
        if (client != null) {
            client.setCameraParams(config, aspect);
        } else {
            log.warning("Received camera params from unknown client.",
                "who", caller.who(), "where", where());
        }
    }

    // documentation inherited from interface TudeySceneProvider
    public void submitActorRequest (ClientObject caller, int actorId, String name)
    {
        // get the client liaison
        int cloid = caller.getOid();
        ClientLiaison client = _clients.get(cloid);
        if (client == null) {
            log.warning("Received actor request from unknown client.",
                "who", caller.who(), "where", where());
            return;
        }

        // get their pawn logic to act as a source
        int pawnId = _tsobj.getPawnId(cloid);
        if (pawnId <= 0) {
            log.warning("User without pawn tried to submit actor request.",
                "who", caller.who());
            return;
        }
        PawnLogic source = (PawnLogic)_actors.get(pawnId);

        // get the target logic
        ActorLogic target = _actors.get(actorId);
        if (target == null) {
            log.warning("Missing actor for request.", "who", caller.who(), "id", actorId);
            return;
        }

        // process the request
        target.request(getNextTimestamp(), source, name);
    }

    // documentation inherited from interface TudeySceneProvider
    public void submitEntryRequest (ClientObject caller, Object key, String name)
    {
        // get the client liaison
        int cloid = caller.getOid();
        ClientLiaison client = _clients.get(cloid);
        if (client == null) {
            log.warning("Received entry request from unknown client.",
                "who", caller.who(), "where", where());
            return;
        }

        // get their pawn logic to act as a source
        int pawnId = _tsobj.getPawnId(cloid);
        if (pawnId <= 0) {
            log.warning("User without pawn tried to submit entry request.",
                "who", caller.who());
            return;
        }
        PawnLogic source = (PawnLogic)_actors.get(pawnId);

        // get the target logic
        EntryLogic target = _entries.get(key);
        if (target == null) {
            log.warning("Missing entry for request.", "who", caller.who(), "key", key);
            return;
        }

        // process the request
        target.request(getNextTimestamp(), source, name);
    }

    // documentation inherited from interface TudeySceneModel.Observer
    public void entryAdded (Entry entry)
    {
        addLogic(entry, true);
    }

    // documentation inherited from interface TudeySceneModel.Observer
    public void entryUpdated (Entry oentry, Entry nentry)
    {
        removeLogic(oentry.getKey());
        addLogic(nentry, true);
    }

    // documentation inherited from interface TudeySceneModel.Observer
    public void entryRemoved (Entry oentry)
    {
        removeLogic(oentry.getKey());
    }

    // documentation inherited from interface ActorAdvancer.Environment
    public TudeySceneModel getSceneModel ()
    {
        return (TudeySceneModel)_scene.getSceneModel();
    }

    // documentation inherited from interface ActorAdvancer.Environment
    public boolean getPenetration (Actor actor, Shape shape, Vector2f result)
    {
        // start with zero penetration
        result.set(Vector2f.ZERO);

        // check the scene model
        ((TudeySceneModel)_scene.getSceneModel()).getPenetration(actor, shape, result);

        // get the intersecting elements
        _actorSpace.getIntersecting(shape, _elements);
        for (int ii = 0, nn = _elements.size(); ii < nn; ii++) {
            SpaceElement element = _elements.get(ii);
            Actor oactor = ((ActorLogic)element.getUserObject()).getActor();
            if (actor.canCollide(oactor)) {
                ((ShapeElement)element).getWorldShape().getPenetration(shape, _penetration);
                if (_penetration.lengthSquared() > result.lengthSquared()) {
                    result.set(_penetration);
                }
            }
        }
        _elements.clear();

        // if our vector is non-zero, we penetrated
        return !result.equals(Vector2f.ZERO);
    }

    // documentation inherited from interface ActorAdvancer.Environment
    public boolean collides (Actor actor, Shape shape)
    {
        return collides(actor, shape, _timestamp);
    }

    // documentation inherited from interface RunQueue
    public void postRunnable (Runnable runnable)
    {
        _runnables.append(runnable);
    }

    // documentation inherited from interface RunQueue
    public boolean isDispatchThread ()
    {
        return _omgr.isDispatchThread();
    }

    // documentation inherited from interface RunQueue
    public boolean isRunning ()
    {
        return _ticker != null;
    }

    @Override // documentation inherited
    protected PlaceObject createPlaceObject ()
    {
        return (_tsobj = new TudeySceneObject());
    }

    @Override // documentation inherited
    protected void didStartup ()
    {
        super.didStartup();

        // get a reference to the scene's config manager
        TudeySceneModel sceneModel = (TudeySceneModel)_scene.getSceneModel();
        _cfgmgr = sceneModel.getConfigManager();

        // create the pathfinder
        _pathfinder = new Pathfinder(this);

        // get a reference to the ticker
        _ticker = getTicker();

        // create logic objects for scene entries and listen for changes
        createEntryLogics(sceneModel);
        sceneModel.addObserver(this);

        // register and fill in our tudey scene service
        _tsobj.setTudeySceneService(addDispatcher(new TudeySceneDispatcher(this)));
    }

    /**
     * Creates logics for an entries that have them.
     */
    protected void createEntryLogics (TudeySceneModel sceneModel)
    {
        // add first, then notify; the entries may be looking for other tagged entries
        for (Entry entry : sceneModel.getEntries()) {
            addLogic(entry, false);
        }
        for (EntryLogic logic : _entries.values()) {
            logic.added();
        }
    }

    @Override // documentation inherited
    protected void didShutdown ()
    {
        super.didShutdown();

        // stop listening to the scene model
        ((TudeySceneModel)_scene.getSceneModel()).removeObserver(this);

        // flag the spaces as disposed to avoid extra unnecessary removal computation
        _actorSpace.dispose();
        _sensorSpace.dispose();

        // destroy/remove all actors
        ActorLogic[] actors = _actors.values().toArray(new ActorLogic[_actors.size()]);
        int timestamp = getNextTimestamp();
        for (ActorLogic logic : actors) {
            logic.destroy(timestamp, logic);
            logic.remove();
        }

        // remove all scene entries
        for (EntryLogic logic : _entries.values()) {
            logic.removed();
        }

        // remove from the ticker
        _ticker.remove(this);
        _ticker = null;

        // shut down the pathfinder
        _pathfinder.shutdown();
        _pathfinder = null;
    }

    @Override // documentation inherited
    protected void bodyEntered (int bodyOid)
    {
        super.bodyEntered(bodyOid);

        // create and map the client liaison
        BodyObject bodyobj = (BodyObject)_omgr.getObject(bodyOid);
        CrowdSession session = (CrowdSession)_clmgr.getClient(bodyobj.username);
        _clients.put(bodyOid, createClientLiaison(bodyobj, session));

        // register with the ticker when the first occupant enters
        if (!_ticker.contains(this)) {
            _lastTick = RunAnywhere.currentTimeMillis();
            _ticker.add(this);
        }
    }

    @Override // documentation inherited
    protected void bodyLeft (int bodyOid)
    {
        super.bodyLeft(bodyOid);

        // remove the client liaison
        _clients.remove(bodyOid);
    }

    @Override // documentation inherited
    protected void placeBecameEmpty ()
    {
        super.placeBecameEmpty();

        // record the time
        _emptyTime = RunAnywhere.currentTimeMillis();
    }

    @Override // documentation inherited
    protected void bodyUpdated (OccupantInfo info)
    {
        super.bodyUpdated(info);

        // pass the information on to the liaison
        _clients.get(info.getBodyOid()).bodyUpdated(info);
    }

    /**
     * Creates the client liaison for the specified body.
     */
    protected ClientLiaison createClientLiaison (BodyObject bodyobj, CrowdSession session)
    {
        return new ClientLiaison(this, bodyobj, session);
    }

    /**
     * Selects a default entrance for an entering player.
     *
     * @return the default entrance, or null if no such entrance is available.
     */
    protected Logic getDefaultEntrance ()
    {
        return _defaultEntrances.isEmpty() ? null : RandomUtil.pickRandom(_defaultEntrances);
    }

    /**
     * Adds the logic object for the specified scene entry, if any.
     *
     * @param notify whether or not to notify the logic that it has been added.
     */
    protected void addLogic (Entry entry, boolean notify)
    {
        String cname = entry.getLogicClassName(_cfgmgr);
        if (cname == null) {
            return;
        }
        EntryLogic logic = (EntryLogic)createLogic(cname);
        if (logic == null) {
            return;
        }
        logic.init(this, entry);
        _entries.put(entry.getKey(), logic);
        addMappings(logic);
        if (notify) {
            logic.added();
        }
    }

    /**
     * Removes the logic object for the specified scene entry, if any.
     */
    protected void removeLogic (Object key)
    {
        EntryLogic logic = _entries.remove(key);
        if (logic != null) {
            removeMappings(logic);
            logic.removed();
        }
    }

    /**
     * Registers the specified logic object unders its mappings.
     */
    public void addMappings (Logic logic)
    {
        for (String tag : logic.getTags()) {
            ArrayList<Logic> list = _tagged.get(tag);
            if (list == null) {
                _tagged.put(tag, list = new ArrayList<Logic>());
            }
            list.add(logic);
        }
        for (Class<?> clazz = logic.getClass(); Logic.class.isAssignableFrom(clazz);
                clazz = clazz.getSuperclass()) {
            ArrayList<Logic> list = _instances.get(clazz);
            if (list == null) {
                _instances.put(clazz, list = new ArrayList<Logic>());
            }
            list.add(logic);
        }
        if (logic.isDefaultEntrance()) {
            _defaultEntrances.add(logic);
        }
    }

    /**
     * Remove the specified logic object from the mappings.
     */
    public void removeMappings (Logic logic)
    {
        for (String tag : logic.getTags()) {
            ArrayList<Logic> list = _tagged.get(tag);
            if (list == null || !list.remove(logic)) {
                log.warning("Missing tag mapping for logic.", "tag", tag, "logic", logic);
                continue;
            }
            if (list.isEmpty()) {
                _tagged.remove(tag);
            }
        }
        for (Class<?> clazz = logic.getClass(); Logic.class.isAssignableFrom(clazz);
                clazz = clazz.getSuperclass()) {
            ArrayList<Logic> list = _instances.get(clazz);
            if (list == null || !list.remove(logic)) {
                log.warning("Missing class mapping for logic.", "class", clazz, "logic", logic);
                continue;
            }
            if (list.isEmpty()) {
                _instances.remove(clazz);
            }
        }
        if (logic.isDefaultEntrance()) {
            _defaultEntrances.remove(logic);
        }
    }

    /**
     * Updates the scene.
     */
    public void tick ()
    {
        // cancel the ticker if enough time has elapsed with no occupants
        long now = RunAnywhere.currentTimeMillis();
        if (_plobj.occupants.size() == 0 && (now - _emptyTime) >= idleTickPeriod()) {
            _ticker.remove(this);
            return;
        }

        // update the scene timestamp
        _previousTimestamp = _timestamp;
        _timestamp += (int)(now - _lastTick);
        _lastTick = now;

        // tick the participants
        _tickOp.init(_timestamp);
        _tickParticipants.apply(_tickOp);

        // process the runnables in the queue
        Runnable runnable;
        while ((runnable = _runnables.getNonBlocking()) != null) {
            runnable.run();
        }

        // post deltas for all clients
        for (ClientLiaison client : _clients.values()) {
            client.postDelta();
        }

        // clear the lists
        _staticActorsAdded.clear();
        _staticActorsUpdated.clear();
        _staticActorsRemoved.clear();
        _effectsFired.clear();

        // note how long the tick took
        _tickDuration = (RunAnywhere.currentTimeMillis() - _lastTick);
    }

    /**
     * Returns a reference to the configuration to use for the specified body's pawn or
     * <code>null</code> for none.
     */
    protected ConfigReference<ActorConfig> getPawnConfig (BodyObject body)
    {
        return null;
    }

    /**
     * Returns the number of milliseconds to continue ticking when there are no occupants in
     * the scene.
     */
    protected long idleTickPeriod ()
    {
        return 5 * 1000L;
    }

    /**
     * Returns the ticker with which to tick the scene.
     */
    protected SceneTicker getTicker ()
    {
        return ((TudeySceneRegistry)_screg).getDefaultTicker();
    }

    /**
     * (Re)used to tick the participants.
     */
    protected static class TickOp
        implements ObserverList.ObserverOp<TickParticipant>
    {
        /**
         * (Re)initializes the op with the current timestamp.
         */
        public void init (int timestamp)
        {
            _timestamp = timestamp;
        }

        // documentation inherited from interface ObserverList.ObserverOp
        public boolean apply (TickParticipant participant)
        {
            return participant.tick(_timestamp);
        }

        /** The timestamp of the current tick. */
        protected int _timestamp;
    }

    /**
     * Base class for actor observer operations.
     */
    protected static abstract class ActorObserverOp
        implements ObserverList.ObserverOp<ActorObserver>
    {
        /**
         * Re(initializes) the op with the provided logic reference.
         *
         * @return a reference to the op, for chaining.
         */
        public ActorObserverOp init (ActorLogic logic)
        {
            _logic = logic;
            return this;
        }

        /** The logic of the actor of interest. */
        protected ActorLogic _logic;
    }

    /** The injector that we use to create and initialize our logic objects. */
    @Inject protected Injector _injector;

    /** The client manager. */
    @Inject protected ClientManager _clmgr;

    /** A casted reference to the Tudey scene object. */
    protected TudeySceneObject _tsobj;

    /** A reference to the scene model's configuration manager. */
    protected ConfigManager _cfgmgr;

    /** The ticker. */
    protected SceneTicker _ticker;

    /** The system time of the last tick. */
    protected long _lastTick;

    /** The duration of processing for the last tick. */
    protected long _tickDuration;

    /** The timestamp of the current and previous ticks. */
    protected int _timestamp, _previousTimestamp;

    /** The time at which the last occupant left. */
    protected long _emptyTime;

    /** The last actor id assigned. */
    protected int _lastActorId;

    /** Maps oids of entering bodies to the keys of the portals through which they're entering. */
    protected HashIntMap<Object> _entering = IntMaps.newHashIntMap();

    /** Maps body oids to client liaisons. */
    protected HashIntMap<ClientLiaison> _clients = IntMaps.newHashIntMap();

    /** The list of participants in the tick. */
    protected ObserverList<TickParticipant> _tickParticipants = ObserverList.newSafeInOrder();

    /** The list of actor observers. */
    protected ObserverList<ActorObserver> _actorObservers = ObserverList.newFastUnsafe();

    /** Scene entry logic objects mapped by key. */
    protected HashMap<Object, EntryLogic> _entries = Maps.newHashMap();

    /** Actor logic objects mapped by id. */
    protected HashIntMap<ActorLogic> _actors = IntMaps.newHashIntMap();

    /** "Static" actors. */
    protected Set<ActorLogic> _staticActors = Sets.newHashSet();

    /** Maps tags to lists of logic objects with that tag. */
    protected HashMap<String, ArrayList<Logic>> _tagged = Maps.newHashMap();

    /** Maps logic classes to lists of logic instances. */
    protected HashMap<Class<?>, ArrayList<Logic>> _instances = Maps.newHashMap();

    /** The logic objects corresponding to default entrances. */
    protected ArrayList<Logic> _defaultEntrances = Lists.newArrayList();

    /** The actor space.  Used to find the actors within a client's area of interest. */
    protected HashSpace _actorSpace = new HashSpace(64f, 6);

    /** The sensor space.  Used to detect mobile objects. */
    protected HashSpace _sensorSpace = new HashSpace(64f, 6);

    /** The pathfinder used for path computation. */
    protected Pathfinder _pathfinder;

    /** The logic for static actors added on the current tick. */
    protected Set<ActorLogic> _staticActorsAdded = Sets.newHashSet();

    /** The logic for static actors updated on the current tick. */
    protected Set<ActorLogic> _staticActorsUpdated = Sets.newHashSet();

    /** The logic for static actors removed on the current tick. */
    protected Set<ActorLogic> _staticActorsRemoved = Sets.newHashSet();

    /** The logic for effects fired on the current tick. */
    protected ArrayList<EffectLogic> _effectsFired = Lists.newArrayList();

    /** Runnables enqueued for the next tick. */
    protected Queue<Runnable> _runnables = Queue.newQueue();

    /** The default local interest region. */
    protected Rect _defaultLocalInterest = TudeySceneMetrics.getDefaultLocalInterest();

    /** Holds collected elements during queries. */
    protected ArrayList<SpaceElement> _elements = Lists.newArrayList();

    /** Holds collected effects during queries. */
    protected ArrayList<Effect> _effects = Lists.newArrayList();

    /** Used to tick the participants. */
    protected TickOp _tickOp = new TickOp();

    /** Used to notify observers of the addition of an actor. */
    protected ActorObserverOp _actorAddedOp = new ActorObserverOp() {
        public boolean apply (ActorObserver observer) {
            observer.actorAdded(_logic);
            return true;
        }
    };

    /** Used to notify observers of the addition of an actor. */
    protected ActorObserverOp _actorRemovedOp = new ActorObserverOp() {
        public boolean apply (ActorObserver observer) {
            observer.actorRemoved(_logic);
            return true;
        }
    };

    /** Stores penetration vector during queries. */
    protected Vector2f _penetration = new Vector2f();
}