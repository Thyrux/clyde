//
// $Id$
//
// Clyde library - tools for developing networked games
// Copyright (C) 2005-2012 Three Rings Design, Inc.
// http://code.google.com/p/clyde/
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

package com.threerings.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.lang.reflect.Array;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

import com.google.common.io.Closer;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import com.samskivert.util.ObserverList;
import com.samskivert.util.StringUtil;

import com.threerings.export.BinaryExporter;
import com.threerings.export.BinaryImporter;
import com.threerings.export.Exportable;
import com.threerings.export.Exporter;
import com.threerings.export.Importer;
import com.threerings.export.XMLExporter;
import com.threerings.export.XMLImporter;
import com.threerings.export.util.LazyFileOutputStream;
import com.threerings.util.Copyable;

import static com.threerings.ClydeLog.log;

/**
 * Contains a group of managed configurations, all of the same class.
 */
public class ConfigGroup<T extends ManagedConfig>
    implements Copyable, Exportable
{
    /**
     * Returns the group name for the specified config class.
     */
    public static String getName (Class<?> clazz)
    {
        String cstr = clazz.getName();
        cstr = cstr.substring(Math.max(cstr.lastIndexOf('.'), cstr.lastIndexOf('$')) + 1);
        cstr = cstr.endsWith("Config") ? cstr.substring(0, cstr.length() - 6) : cstr;
        return StringUtil.toUSLowerCase(StringUtil.unStudlyName(cstr));
    }

    /**
     * Creates a new config group for the specified class.
     */
    public ConfigGroup (Class<T> clazz)
    {
        initConfigClass(clazz);
    }

    /**
     * No-arg constructor for deserialization.
     */
    public ConfigGroup ()
    {
    }

    /**
     * Initializes this group.
     */
    public void init (ConfigManager cfgmgr)
    {
        _cfgmgr = cfgmgr;

        // load the existing configurations (first checking for an xml file, then a binary file)
        if (_cfgmgr.getConfigPath() != null && (readConfigs(true) || readConfigs(false))) {
            log.debug("Read configurations for group " + _name + ".");
        }

        // provide the configurations with a reference to the manager
        for (T config : _configsByName.values()) {
            config.init(_cfgmgr);
        }
    }

    /**
     * Returns the name of this group.
     */
    public String getName ()
    {
        return _name;
    }

    /**
     * Returns the class of the configurations in this group.
     */
    public Class<T> getConfigClass ()
    {
        return _cclass;
    }

    /**
     * Retrieves a configuration by name.
     */
    public T getConfig (String name)
    {
        return _configsByName.get(name);
    }

    /**
     * Returns the collection of all registered configurations.
     */
    public Collection<T> getConfigs ()
    {
        return _configsByName.values();
    }

    /**
     * Adds a listener for configuration events.
     */
    public void addListener (ConfigGroupListener<T> listener)
    {
        if (_listeners == null) {
            _listeners = ObserverList.newFastUnsafe();
        }
        _listeners.add(listener);
    }

    /**
     * Removes a configuration event listener.
     */
    public void removeListener (ConfigGroupListener<T> listener)
    {
        if (_listeners != null) {
            _listeners.remove(listener);
            if (_listeners.isEmpty()) {
                _listeners = null;
            }
        }
    }

    /**
     * Adds all of the supplied configurations to the set.
     */
    public void addConfigs (Collection<T> configs)
    {
        for (T config : configs) {
            addConfig(config);
        }
    }

    /**
     * Adds a configuration to the set.
     */
    public void addConfig (T config)
    {
        _configsByName.put(config.getName(), config);
        config.init(_cfgmgr);
        fireConfigAdded(config);
    }

    /**
     * Removes a configuration from the set.
     */
    public void removeConfig (T config)
    {
        _configsByName.remove(config.getName());
        fireConfigRemoved(config);
    }

    /**
     * Saves this group's configurations.
     */
    public final void save ()
    {
        save(getConfigFile(true));
    }

    /**
     * Saves this group's configurations to the specified file.
     */
    public final void save (File file)
    {
        save(_configsByName.values(), file, true);
    }

    /**
     * Saves this group's configurations to the specified file, o
     */
    public final void save (File file, boolean xml)
    {
        save(_configsByName.values(), file, xml);
    }

    /**
     * Saves the provided collection of configurations to a file.
     */
    public final void save (Collection<T> configs, File file)
    {
        save(configs, file, true);
    }

    /**
     * Save the specified configs
     */
    public void save (Collection<T> configs, File file, boolean xml)
    {
        try {
            Closer closer = Closer.create();
            try {
                LazyFileOutputStream stream = closer.register(new LazyFileOutputStream(file));
                Exporter xport = closer.register(
                        xml ? new XMLExporter(stream) : new BinaryExporter(stream));
                xport.writeObject(toSortedArray(configs));

            } finally {
                closer.close();
            }

        } catch (IOException e) {
            log.warning("Error writing configurations [file=" + file + "].", e);
        }
    }

    /**
     * Reverts to the last saved configurations.
     */
    public void revert ()
    {
        load(getConfigFile(true));
    }

    /**
     * Loads the configurations from the specified file.
     */
    public void load (File file)
    {
        load(file, false);
    }

    /**
     * Loads the configurations from the specified file.
     *
     * @param merge if true, merge with the existing configurations; do not delete configurations
     * that do not exist in the file.
     */
    public void load (File file, boolean merge)
    {
        // read in the array of configurations
        Object array;
        try {
            Importer in = new XMLImporter(new FileInputStream(file));
            array = in.readObject();
            in.close();

        } catch (IOException e) {
            log.warning("Error reading configurations [file=" + file + "].", e);
            return;
        }
        @SuppressWarnings("unchecked") T[] nconfigs = (T[])array;
        validateOuters(nconfigs);
        load(Arrays.asList(nconfigs), merge, false);
    }

    /**
     * Writes the fields of this object.
     */
    public void writeFields (Exporter out)
        throws IOException
    {
        // write the sorted configs out as a raw object
        out.write("configs", toSortedArray(_configsByName.values()), null, Object.class);
    }

    /**
     * Reads the fields of this object.
     */
    public void readFields (Importer in)
        throws IOException
    {
        // read in the configs and determine the type
        Object object = in.read("configs", null, Object.class);
        @SuppressWarnings("unchecked") T[] configs = (T[])(
            object == null ? new ManagedConfig[0] : object);
        @SuppressWarnings("unchecked") Class<T> clazz =
            (Class<T>)configs.getClass().getComponentType();
        initConfigClass(clazz);

        // populate the maps
        initConfigs(configs);
    }

    // documentation inherited from interface Copyable
    public Object copy (Object dest)
    {
        return copy(dest, null);
    }

    // documentation inherited from interface Copyable
    public Object copy (Object dest, Object outer)
    {
        @SuppressWarnings("unchecked") ConfigGroup<T> other =
            (dest instanceof ConfigGroup) ? (ConfigGroup<T>)dest : new ConfigGroup<T>(_cclass);
        other.load(_configsByName.values(), false, true);
        return other;
    }

    /**
     * Initializes the configuration class immediately after construction or deserialization.
     */
    protected void initConfigClass (Class<T> clazz)
    {
        _cclass = clazz;
        _name = getName(clazz);
    }

    /**
     * Attempts to read the initial set of configurations.
     *
     * @return true if successful, false otherwise.
     */
    protected boolean readConfigs (boolean xml)
    {
        InputStream stream = getConfigStream(xml);
        if (stream == null) {
            return false;
        }
        try {
            Importer in = xml ? new XMLImporter(stream) : new BinaryImporter(stream);
            @SuppressWarnings("unchecked") T[] configs = (T[])in.readObject();
            if (xml) {
                validateOuters(configs);
            }
            initConfigs(configs);
            in.close();
            return true;

        } catch (Exception e) { // IOException, ClassCastException
            log.warning("Error reading configurations.", "group", _name, e);
            return false;
        }
    }

    /**
     * Returns the configuration stream, or <code>null</code> if it doesn't exist.
     */
    protected InputStream getConfigStream (boolean xml)
    {
        try {
            return _cfgmgr.getResourceManager().getResource(getConfigPath(xml));
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the configuration file.
     */
    protected File getConfigFile (boolean xml)
    {
        return _cfgmgr.getResourceManager().getResourceFile(getConfigPath(xml));
    }

    /**
     * Returns the path of the config resource associated with this group.
     */
    protected String getConfigPath (boolean xml)
    {
        return _cfgmgr.getConfigPath() + _name + (xml ? ".xml" : ".dat");
    }

    /**
     * Validates the outer object references of the supplied configs.
     */
    protected void validateOuters (T[] configs)
    {
        for (T config : configs) {
            config.validateOuters(_name + ":" + config.getName());
        }
    }

    /**
     * Sets the initial set of configs.
     */
    protected void initConfigs (T[] configs)
    {
        for (T config : configs) {
            _configsByName.put(config.getName(), config);
        }
    }

    /**
     * Loads the specified configurations.
     *
     * @param merge if true, merge with the existing configurations; do not delete configurations
     * that do not exist in the collection.
     * @param clone if true, we must clone configurations that do not yet exist in the group.
     */
    protected void load (Collection<T> nconfigs, boolean merge, boolean clone)
    {
        // add any configurations that don't already exist and update those that do
        HashSet<String> names = new HashSet<String>();
        for (T nconfig : nconfigs) {
            String name = nconfig.getName();
            names.add(name);
            T oconfig = _configsByName.get(name);
            if (oconfig == null) {
                addConfig(clone ? _cclass.cast(nconfig.clone()) : nconfig);
            } else if (!nconfig.equals(oconfig)) {
                nconfig.copy(oconfig);
                oconfig.wasUpdated();
            }
        }
        if (merge) {
            return;
        }

        // remove any configurations not present in the array (if not merging)
        for (T oconfig : Lists.newArrayList(_configsByName.values())) {
            if (!names.contains(oconfig.getName())) {
                removeConfig(oconfig);
            }
        }
    }

    /**
     * Converts the supplied collection of configs to a sorted array.
     */
    protected T[] toSortedArray (Collection<T> configs)
    {
        return Iterables.toArray(ManagedConfig.NAME_ORDERING.immutableSortedCopy(configs), _cclass);
    }

    /**
     * Fires a configuration added event.
     */
    protected void fireConfigAdded (T config)
    {
        if (_listeners == null) {
            return;
        }
        final ConfigEvent<T> event = new ConfigEvent<T>(this, config);
        _listeners.apply(new ObserverList.ObserverOp<ConfigGroupListener<T>>() {
            public boolean apply (ConfigGroupListener<T> listener) {
                listener.configAdded(event);
                return true;
            }
        });
    }

    /**
     * Fires a configuration removed event.
     */
    protected void fireConfigRemoved (T config)
    {
        if (_listeners == null) {
            return;
        }
        final ConfigEvent<T> event = new ConfigEvent<T>(this, config);
        _listeners.apply(new ObserverList.ObserverOp<ConfigGroupListener<T>>() {
            public boolean apply (ConfigGroupListener<T> listener) {
                listener.configRemoved(event);
                return true;
            }
        });
    }

    /** The configuration manager that created this group. */
    protected ConfigManager _cfgmgr;

    /** The name of this group. */
    protected String _name;

    /** The configuration class. */
    protected Class<T> _cclass;

    /** Configurations mapped by name. */
    protected HashMap<String, T> _configsByName = new HashMap<String, T>();

    /** Configuration event listeners. */
    protected ObserverList<ConfigGroupListener<T>> _listeners;
}
