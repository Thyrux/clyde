//
// $Id$

package com.threerings.opengl.scene;

import com.threerings.opengl.renderer.Light;

/**
 * Represents the influence of a light.
 */
public class LightInfluence extends SceneInfluence
{
    /**
     * Creates a new light influence.
     */
    public LightInfluence (Light light)
    {
        _light = light;
    }

    /**
     * Returns a reference to the light object.
     */
    public Light getLight ()
    {
        return _light;
    }

    /** The light object. */
    protected Light _light;
}
