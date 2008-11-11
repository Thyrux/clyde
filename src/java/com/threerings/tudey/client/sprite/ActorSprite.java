//
// $Id$

package com.threerings.tudey.client.sprite;

import com.threerings.opengl.util.GlContext;

import com.threerings.tudey.client.TudeySceneView;
import com.threerings.tudey.data.Actor;

/**
 * Represents an active element of the scene.
 */
public abstract class ActorSprite extends Sprite
{
    /**
     * Creates a new actor sprite.
     */
    public ActorSprite (GlContext ctx, TudeySceneView view)
    {
        super(ctx, view);
    }
}
