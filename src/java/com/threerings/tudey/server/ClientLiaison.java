//
// $Id$
//
// Clyde library - tools for developing networked games
// Copyright (C) 2005-2009 Three Rings Design, Inc.
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

import com.samskivert.util.CollectionUtil;
import com.samskivert.util.HashIntMap;
import com.samskivert.util.StringUtil;

import com.threerings.presents.net.Transport;

import com.threerings.crowd.data.BodyObject;
import com.threerings.crowd.data.OccupantInfo;

import com.threerings.math.Rect;
import com.threerings.math.SphereCoords;
import com.threerings.math.Vector2f;

import com.threerings.tudey.data.InputFrame;
import com.threerings.tudey.data.TudeyOccupantInfo;
import com.threerings.tudey.data.TudeySceneObject;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.data.effect.Effect;
import com.threerings.tudey.dobj.ActorDelta;
import com.threerings.tudey.dobj.SceneDeltaEvent;
import com.threerings.tudey.server.logic.PawnLogic;
import com.threerings.tudey.util.TudeySceneMetrics;

import static com.threerings.tudey.Log.*;

/**
 * Handles interaction with a single client.
 */
public class ClientLiaison
{
    /**
     * Creates a new liaison for the specified client.
     */
    public ClientLiaison (TudeySceneManager scenemgr, BodyObject bodyobj)
    {
        _scenemgr = scenemgr;
        _tsobj = (TudeySceneObject)scenemgr.getPlaceObject();
        _bodyobj = bodyobj;

        // find the client's initial target
        int targetId = _tsobj.getPawnId(bodyobj.getOid());
        if (targetId > 0) {
            _targetControlled = true;
        } else {
            targetId = _tsobj.getFirstPawnId();
        }
        _target = (PawnLogic)_scenemgr.getActorLogic(targetId);

        // insert the baseline (empty) tick record
        _records.add(new TickRecord(0, new HashIntMap<Actor>(), new Effect[0]));
    }

    /**
     * Notes that the client's occupant info has been updated.
     */
    public void bodyUpdated (OccupantInfo info)
    {
        if (info.status == OccupantInfo.DISCONNECTED) {
            _records.clear();
        } else {
            if (_records.isEmpty()) {
                // start again from the zero reference time
                _records.add(new TickRecord(0, new HashIntMap<Actor>(), new Effect[0]));
            }
        }
    }

    /**
     * Sets the client's target actor.
     */
    public void setTarget (PawnLogic target)
    {
        _target = target;
    }

    /**
     * Sets the client's camera parameters.
     */
    public void setCameraParams (
        float fovy, float aspect, float near, float far, SphereCoords coords)
    {
        _localInterest = TudeySceneMetrics.getLocalInterest(fovy, aspect, near, far, coords);
    }

    /**
     * Processes a request to enqueue input received from a client.
     *
     * @param ping the ping calculated from the current time and the client's time estimate.
     */
    public void enqueueInput (int acknowledge, int ping, InputFrame[] frames)
    {
        // acknowledgement cannot decrease; if it has, this must be out-of-order
        if (acknowledge < _records.get(0).getTimestamp()) {
            return;
        }

        // remove all tick records up to the acknowledgement
        while (acknowledge > _records.get(0).getTimestamp()) {
            _records.remove(0);
        }

        // remember ping
        _ping = ping;

        // if we do not control the target, we do not process the input
        if (!_targetControlled) {
            if (frames.length > 0) {
                log.warning("Got input frames for non-controlled pawn.",
                    "who", _bodyobj.who(), "actor", _target.getActor());
            }
            return;
        }

        // enqueue input frames
        int timestamp = _scenemgr.getTimestamp();
        for (InputFrame frame : frames) {
            int input = frame.getTimestamp();
            if (input <= _lastInput) {
                continue; // already processed
            }
            _lastInput = input;
            if (input <= timestamp) {
                continue; // out of date
            }
            _target.enqueueInput(frame);
        }
    }

    /**
     * Posts the scene delta for this client, informing it any all relevant changes to the scene
     * since its last acknowledged delta.
     */
    public void postDelta ()
    {
        // no need to do anything if disconnected
        if (_bodyobj.status == OccupantInfo.DISCONNECTED) {
            return;
        }

        // translate the local interest bounds based on the actor translation
        Vector2f translation = _target.getActor().getTranslation();
        _localInterest.getMinimumExtent().add(translation, _worldInterest.getMinimumExtent());
        _localInterest.getMaximumExtent().add(translation, _worldInterest.getMaximumExtent());

        // retrieve the states of the actors, effects fired in the client's area of interest
        HashIntMap<Actor> actors = getActorSnapshots();
        Effect[] effectsFired = _scenemgr.getEffectsFired(_worldInterest);

        // record the tick
        int timestamp = _scenemgr.getTimestamp();
        _records.add(new TickRecord(timestamp, actors, effectsFired));

        // the last acknowledged tick is the reference
        TickRecord reference = _records.get(0);

        // find all actors added, updated since the reference
        HashIntMap<Actor> oactors = reference.getActors();
        for (Actor nactor : actors.values()) {
            Actor oactor = oactors.get(nactor.getId());
            if (oactor == null) {
                _added.add(nactor);
            } else if (!oactor.equals(nactor)) {
                _updated.add(new ActorDelta(oactor, nactor));
            }
        }

        // find all actors removed
        for (Actor oactor : oactors.values()) {
            int id = oactor.getId();
            if (!actors.containsKey(id)) {
                _removed.add(id);
            }
        }

        // get all effects fired (not expired)
        for (int ii = 1, nn = _records.size(); ii < nn; ii++) {
            for (Effect effect : _records.get(ii).getEffectsFired()) {
                if (timestamp < effect.getExpiry()) {
                    _fired.add(effect);
                }
            }
        }

        // create and post the event
        _bodyobj.postEvent(new SceneDeltaEvent(
            _bodyobj.getOid(), _tsobj.getOid(), _lastInput, _ping,
            reference.getTimestamp(), timestamp,
            _added.isEmpty() ? null : _added.toArray(new Actor[_added.size()]),
            _updated.isEmpty() ? null : _updated.toArray(new ActorDelta[_updated.size()]),
            _removed.isEmpty() ? null : CollectionUtil.toIntArray(_removed),
            _fired.isEmpty() ? null : _fired.toArray(new Effect[_fired.size()]),
            Transport.UNRELIABLE_UNORDERED));

        // clear the arrays
        _added.clear();
        _updated.clear();
        _removed.clear();
        _fired.clear();
    }

    /**
     * Returns a map containing snapshots of all actors in the client's area of interest.
     */
    protected HashIntMap<Actor> getActorSnapshots ()
    {
        return _scenemgr.getActorSnapshots(_worldInterest);
    }

    /**
     * Contains the state at a single tick.
     */
    protected static class TickRecord
    {
        /**
         * Creates a new record.
         */
        public TickRecord (int timestamp, HashIntMap<Actor> actors, Effect[] effectsFired)
        {
            _timestamp = timestamp;
            _actors = actors;
            _effectsFired = effectsFired;
        }

        /**
         * Returns the timestamp of this record.
         */
        public int getTimestamp ()
        {
            return _timestamp;
        }

        /**
         * Returns the state of the actors at this tick.
         */
        public HashIntMap<Actor> getActors ()
        {
            return _actors;
        }

        /**
         * Returns the effects fired on this tick.
         */
        public Effect[] getEffectsFired ()
        {
            return _effectsFired;
        }

        /** The timestamp of this record. */
        protected int _timestamp;

        /** The actor states at this tick. */
        protected HashIntMap<Actor> _actors;

        /** The effects fired on this tick. */
        protected Effect[] _effectsFired;
    }

    /** The scene manager that created the liaison. */
    protected TudeySceneManager _scenemgr;

    /** The scene object. */
    protected TudeySceneObject _tsobj;

    /** The client body object. */
    protected BodyObject _bodyobj;

    /** The pawn that the client's camera is tracking. */
    protected PawnLogic _target;

    /** Whether or not the client is controlling the target pawn. */
    protected boolean _targetControlled;

    /** The untranslated area of interest. */
    protected Rect _localInterest = TudeySceneMetrics.getDefaultLocalInterest();

    /** The translated area of interest. */
    protected Rect _worldInterest = new Rect();

    /** Records of each update transmitted to the client. */
    protected ArrayList<TickRecord> _records = new ArrayList<TickRecord>();

    /** The ping time estimate. */
    protected int _ping;

    /** The timestamp of the last input frame received from the client. */
    protected int _lastInput;

    /** Stores added actors. */
    protected ArrayList<Actor> _added = new ArrayList<Actor>();

    /** Stores updated actor deltas. */
    protected ArrayList<ActorDelta> _updated = new ArrayList<ActorDelta>();

    /** Stores removed actor ids. */
    protected ArrayList<Integer> _removed = new ArrayList<Integer>();

    /** Stores effects fired. */
    protected ArrayList<Effect> _fired = new ArrayList<Effect>();
}
