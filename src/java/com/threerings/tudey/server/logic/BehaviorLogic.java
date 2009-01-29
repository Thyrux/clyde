//
// $Id$

package com.threerings.tudey.server.logic;

import java.util.ArrayList;

import com.google.common.collect.Lists;

import com.samskivert.util.ArrayUtil;
import com.samskivert.util.RandomUtil;

import com.threerings.math.FloatMath;
import com.threerings.math.Vector2f;

import com.threerings.tudey.config.BehaviorConfig;
import com.threerings.tudey.data.actor.Actor;
import com.threerings.tudey.data.actor.Mobile;
import com.threerings.tudey.server.TudeySceneManager;

/**
 * Handles the server-side processing for agent behavior.
 */
public abstract class BehaviorLogic extends Logic
{
    /**
     * Handles the idle behavior.
     */
    public static class Idle extends BehaviorLogic
    {
    }

    /**
     * Superclass of the evaluating behaviors.
     */
    public static abstract class Evaluating extends BehaviorLogic
    {
        @Override // documentation inherited
        public void tick (int timestamp)
        {
            // if scheduled to do so, evaluate
            if (timestamp >= _nextEvaluation) {
                evaluate();
            }
        }

        /**
         * Performs an evaluation.  Default implementation simply schedules the next evaluation.
         */
        protected void evaluate ()
        {
            scheduleNextEvaluation();
        }

        /**
         * Schedules the next evaluation.
         */
        protected void scheduleNextEvaluation ()
        {
            _nextEvaluation = _scenemgr.getTimestamp() +
                (int)(((BehaviorConfig.Evaluating)_config).evaluationInterval.getValue() * 1000f);
        }

        /**
         * Postpones the next evaluation until the next rescheduling.
         */
        protected void postponeNextEvaluation ()
        {
            _nextEvaluation = Integer.MAX_VALUE;
        }

        /** The time for which the next evaluation is scheduled. */
        protected int _nextEvaluation;
    }

    /**
     * Handles the wander behavior.
     */
    public static class Wander extends Evaluating
    {
        @Override // documentation inherited
        public void tick (int timestamp)
        {
            super.tick(timestamp);

            // if we have exceeded the radius and are moving away from the origin, change direction
            Actor actor = _agent.getActor();
            Vector2f trans = _agent.getTranslation();
            if (actor.isSet(Mobile.MOVING) &&
                    trans.distance(_origin) > ((BehaviorConfig.Wander)_config).radius) {
                float angle = FloatMath.atan2(_origin.y - trans.y, _origin.x - trans.x);
                float rotation = _agent.getActor().getRotation();
                if (FloatMath.getAngularDistance(angle, rotation) > FloatMath.HALF_PI) {
                    changeDirection(angle);
                }
            }
        }

        @Override // documentation inherited
        public void reachedTargetRotation ()
        {
            _agent.startMoving();
            scheduleNextEvaluation();
        }

        @Override // documentation inherited
        public void penetratedEnvironment (Vector2f penetration)
        {
            // change the direction, using the reflected direction as a base
            float rotation = FloatMath.normalizeAngle(
                _agent.getActor().getRotation() + FloatMath.PI);
            if (penetration.length() > FloatMath.EPSILON) {
                float angle = FloatMath.atan2(penetration.y, penetration.x);
                rotation = FloatMath.normalizeAngle(
                    angle - FloatMath.getAngularDifference(rotation, angle));
            }
            changeDirection(rotation);
        }

        @Override // documentation inherited
        protected void didInit ()
        {
            _origin.set(_agent.getTranslation());
        }

        @Override // documentation inherited
        protected void evaluate ()
        {
            super.evaluate();
            changeDirection();
        }

        /**
         * Changes the direction of the agent.
         */
        protected void changeDirection ()
        {
            changeDirection(_agent.getActor().getRotation());
        }

        /**
         * Changes the direction of the agent.
         *
         * @param rotation the rotation to use as a base.
         */
        protected void changeDirection (float rotation)
        {
            _agent.stopMoving();
            float delta = ((BehaviorConfig.Wander)_config).directionChange.getValue();
            postponeNextEvaluation();
            _agent.setTargetRotation(FloatMath.normalizeAngle(rotation + delta));
        }

        /** The translation of the actor when initialized. */
        protected Vector2f _origin = new Vector2f();
    }

    /**
     * Base class for behaviors that involve following paths.
     */
    public static abstract class Pathing extends Evaluating
    {
        @Override // documentation inherited
        public void tick (int timestamp)
        {
            super.tick(timestamp);
            if (_path == null) {
                return; // nothing to do
            }

            // see if we've reached the current node (looping around in case the notification
            // sets us on a new path)
            Vector2f trans = _agent.getTranslation();
            while (_path[_pidx].distance(trans) <= getReachRadius()) {
                if (++_pidx == _path.length) {
                    _agent.stopMoving();
                    _path = null;
                    completedPath();
                } else {
                    reachedPathIndex(_pidx - 1);
                }
                if (_path == null) {
                    return;
                }
            }
            // make sure we're facing the right direction
            Vector2f node = _path[_pidx];
            float rot = FloatMath.atan2(node.y - trans.y, node.x - trans.x);
            if (FloatMath.getAngularDistance(_agent.getRotation(), rot) > 0.0001f) {
                _agent.stopMoving();
                _agent.setTargetRotation(rot);
            } else {
                _agent.startMoving();
            }
        }

        /**
         * Sets the path to follow.
         */
        protected void setPath (Vector2f[] path)
        {
            _path = path;
            _pidx = 0;
        }

        /**
         * Returns the radius within which we can be consider ourselves to have reached a node
         * (which depends on the actor's speed, since it's possible to overshoot).
         */
        protected float getReachRadius ()
        {
            // radius is the distance we can travel in a single tick
            float speed = ((Mobile)_agent.getActor()).getSpeed();
            return speed / _scenemgr.getTicksPerSecond();
        }

        /**
         * Called when we reach each node in the path (except for the last one, for which we call
         * {@link #completedPath}.
         */
        protected void reachedPathIndex (int idx)
        {
            // nothing by default
        }

        /**
         * Called when we complete the set path.
         */
        protected void completedPath ()
        {
            // nothing by default
        }

        /** The waypoints of the path being followed. */
        protected Vector2f[] _path;

        /** The index of the next point on the path. */
        protected int _pidx;
    }

    /**
     * Handles the follow behavior.
     */
    public static class Follow extends Pathing
    {

    }

    /**
     * Handles the patrol behavior.
     */
    public static class Patrol extends Pathing
    {
        @Override // documentation inherited
        protected void didInit ()
        {
            _target = createTarget(((BehaviorConfig.Patrol)_config).target, _agent);
        }

        @Override // documentation inherited
        protected void evaluate ()
        {
            super.evaluate();

            // determine the square of the branch radius
            float br2;
            if (_path == null) {
                br2 = Float.MAX_VALUE;
            } else {
                float radius = ((BehaviorConfig.Patrol)_config).branchRadius;
                if (radius < 0f) {
                    return; // no possibility of branching
                }
                br2 = radius*radius;
            }

            // resolve the target paths
            _target.resolve(_agent, _targets);
            Vector2f trans = _agent.getTranslation();
            for (int ii = 0, nn = _targets.size(); ii < nn; ii++) {
                Logic target = _targets.get(ii);
                if (target == _currentTarget) {
                    continue;
                }
                Vector2f[] path = _targets.get(ii).getPatrolPath();
                if (path == null) {
                    continue;
                }
                // find the index of the closest node on the path
                float cdist = Float.MAX_VALUE;
                int cidx = -1;
                for (int jj = 0, mm = path.length; jj < mm; jj++) {
                    float dist = path[jj].distanceSquared(trans);
                    if (dist < cdist) {
                        cidx = jj;
                        cdist = dist;
                    }
                }
                if (cdist <= br2) {
                    _candidates.add(new PathCandidate(target, path, cidx));
                }
            }
            _targets.clear();

            // pick a candidate at random
            if (_candidates.isEmpty()) {
                return;
            }
            if (_path != null) {
                _candidates.add(null); // represents the current path
            }
            PathCandidate candidate = RandomUtil.pickRandom(_candidates);
            _candidates.clear();

            // set off on that path
            if (candidate != null) {
                setPath(candidate.getRemainingPath(_agent.getRotation()), candidate.getTarget());
            }
        }

        @Override // documentation inherited
        protected void reachedPathIndex (int idx)
        {
            evaluate();
        }

        @Override // documentation inherited
        protected void completedPath ()
        {
            _currentTarget = null;
            evaluate();
        }

        /**
         * Sets the path to traverse.
         */
        protected void setPath (Vector2f[] path, Logic currentTarget)
        {
            super.setPath(path);
            _currentTarget = currentTarget;
        }

        /** The target to patrol. */
        protected TargetLogic _target;

        /** Holds targets during processing. */
        protected ArrayList<Logic> _targets = Lists.newArrayList();

        /** Holds candidate paths during processing. */
        protected ArrayList<PathCandidate> _candidates = Lists.newArrayList();

        /** The logic corresponding to the current path, if any. */
        protected Logic _currentTarget;
    }

    /**
     * Initializes the logic.
     */
    public void init (TudeySceneManager scenemgr, BehaviorConfig config, AgentLogic agent)
    {
        super.init(scenemgr);
        _config = config;
        _agent = agent;

        // give subclasses a chance to initialize
        didInit();
    }

    /**
     * Ticks the behavior.
     */
    public void tick (int timestamp)
    {
        // nothing by default
    }

    /**
     * Notifies the behavior that the agent has reached its target rotation.
     */
    public void reachedTargetRotation ()
    {
        // nothing by default
    }

    /**
     * Notifies the behavior that the agent has penetrated its environment during advancement.
     *
     * @param penetration the sum penetration vector.
     */
    public void penetratedEnvironment (Vector2f penetration)
    {
        // nothing by default
    }

    @Override // documentation inherited
    public Vector2f getTranslation ()
    {
        return _agent.getTranslation();
    }

    @Override // documentation inherited
    public float getRotation ()
    {
        return _agent.getRotation();
    }

    /**
     * Override to perform custom initialization.
     */
    protected void didInit ()
    {
        // nothing by default
    }

    /**
     * A candidate path under consideration for branching.
     */
    protected static class PathCandidate
    {
        /**
         * Creates a new path candidate.
         */
        public PathCandidate (Logic target, Vector2f[] path, int cidx)
        {
            _target = target;
            _path = path;
            _cidx = cidx;
        }

        /**
         * Returns a reference to the logic associated with the path.
         */
        public Logic getTarget ()
        {
            return _target;
        }

        /**
         * Returns the portion of the path from the closest node to the end, using the supplied
         * angle to determine which direction to travel along the path in cases of ambiguity.
         * This clobbers the contained path.
         */
        public Vector2f[] getRemainingPath (float angle)
        {
            if (_cidx == _path.length - 1) { // last node; use reverse direction
                ArrayUtil.reverse(_path);
            } else if (_cidx != 0) { // internal node; alignment determines direction
                Vector2f prev = _path[_cidx - 1], node = _path[_cidx], next = _path[_cidx + 1];
                float df = FloatMath.atan2(next.y - node.y, next.x - node.x);
                float dr = FloatMath.atan2(prev.y - node.y, prev.x - node.x);
                if (FloatMath.getAngularDistance(angle, dr) <
                        FloatMath.getAngularDistance(angle, df)) {
                    ArrayUtil.reverse(_path = ArrayUtil.splice(_path, _cidx + 1));
                } else {
                    _path = ArrayUtil.splice(_path, 0, _cidx);
                }
            }
            return _path;
        }

        /** The logic with which the path is associated. */
        protected Logic _target;

        /** The base path. */
        protected Vector2f[] _path;

        /** The index of the closest node on the path. */
        protected int _cidx;
    }

    /** The behavior configuration. */
    protected BehaviorConfig _config;

    /** The controlled agent. */
    protected AgentLogic _agent;
}
