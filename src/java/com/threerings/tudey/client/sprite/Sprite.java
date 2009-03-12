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

package com.threerings.tudey.client.sprite;

import com.threerings.expr.Scoped;
import com.threerings.expr.SimpleScope;

import com.threerings.tudey.client.TudeySceneView;
import com.threerings.tudey.util.TudeyContext;

/**
 * Represents a placeable object.
 */
public abstract class Sprite extends SimpleScope
{
    /**
     * Creates a new sprite.
     */
    public Sprite (TudeyContext ctx, TudeySceneView view)
    {
        super(view);
        _ctx = ctx;
        _view = view;
    }

    /**
     * Determines whether the sprite is hoverable (for purposes of in-game user interaction).
     */
    public boolean isHoverable ()
    {
        return false;
    }

    /**
     * Sets the sprite's hover state.
     */
    public void setHover (boolean hover)
    {
        throw new UnsupportedOperationException();
    }

    @Override // documentation inherited
    public String getScopeName ()
    {
        return "sprite";
    }

    /** The application context. */
    protected TudeyContext _ctx;

    /** The parent view. */
    @Scoped
    protected TudeySceneView _view;
}
