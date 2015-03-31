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

package com.threerings.export;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;

import java.util.zip.InflaterInputStream;

import com.google.common.base.Supplier;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;

import com.samskivert.util.HashIntMap;

import com.threerings.util.ReflectionUtil;

import static com.threerings.export.Log.log;

/**
 * Imports from the compact binary format generated by {@link BinaryExporter}.
 */
public class BinaryImporter extends Importer
{
    // TEMP?
    // Added so that we can import old data
//    public static void addMapping (String name, Class<?> clazz)
//    {
//        ClassWrapper wrapper = new ClassWrapper(null, clazz);
//        wrapper._name = name;
//        _staticMappings.put(name, wrapper);
//
//        String arrayName = "[L" + name + ";";
//        ClassWrapper arrayWrapper = new ClassWrapper(
//            arrayName, Array.newInstance(clazz, 0).getClass(), wrapper);
//        _staticMappings.put(arrayName, arrayWrapper);
//    }

    /**
     * Creates an importer to read from the specified stream.
     */
    public BinaryImporter (InputStream in)
    {
        _in = new DataInputStream(_base = in);

        // populate the class map with the bootstrap classes
        for (int ii = 0; ii < BinaryExporter.BOOTSTRAP_CLASSES.length; ii++) {
            _classes.put(ii + 1, getClassWrapper(BinaryExporter.BOOTSTRAP_CLASSES[ii]));
        }
        // get these by reference so we don't have to keep looking them up
        _objectClass = getClassWrapper(Object.class);
        _stringClass = getClassWrapper(String.class);
    }

    @Override
    public Object readObject ()
        throws IOException
    {
        if (_objects == null) {
            // verify the preamble
            int magic = _in.readInt();
            if (magic != BinaryExporter.MAGIC_NUMBER) {
                throw new IOException(String.format("Invalid magic number [magic=%#x].", magic));
            }
            short version = _in.readShort();
            switch (version) {
                default:
                    throw new IOException(String.format("Invalid version [version=%#x].", version));

                case BinaryExporter.VERSION:
                    _idReaderSupplier = new Supplier<IdReader>() {
                                public IdReader get () {
                                    return new VarIntReader();
                                }
                            };
                    break;

                case IntermediateIdReader.VERSION:
                    _idReaderSupplier = new Supplier<IdReader>() {
                                public IdReader get () {
                                    return new IntermediateIdReader();
                                }
                            };
                    break;

                case ClassicIdReader.VERSION:
                    _idReaderSupplier = new Supplier<IdReader>() {
                                public IdReader get () {
                                    return new ClassicIdReader();
                                }
                            };
                    break;
            }
            short flags = _in.readShort();
            boolean compressed = (flags & BinaryExporter.COMPRESSED_FORMAT_FLAG) != 0;

            // the rest of the stream may be compressed
            if (compressed) {
                _in = new DataInputStream(new InflaterInputStream(_base));
            }

            _objectIdReader = _idReaderSupplier.get();
            _classIdReader = _idReaderSupplier.get();

            // initialize mapping
            _objects = new HashIntMap<Object>();
            _objects.put(0, NULL);
        }
        return read(_objectClass);
    }

    @Override
    public boolean read (String name, boolean defvalue)
        throws IOException
    {
        try {
            Boolean value = (Boolean)_fields.get(name);
            return (value == null) ? defvalue : value;
        } catch (ClassCastException e) {
            log.warning("Can't cast to boolean.", "name", name, "value", _fields.get(name), e);
            return defvalue;
        }
    }

    @Override
    public byte read (String name, byte defvalue)
        throws IOException
    {
        try {
            Number value = (Number)_fields.get(name);
            return (value == null) ? defvalue : value.byteValue();
        } catch (ClassCastException e) {
            log.warning("Can't cast to byte.", "name", name, "value", _fields.get(name), e);
            return defvalue;
        }
    }

    @Override
    public char read (String name, char defvalue)
        throws IOException
    {
        try {
            Character value = (Character)_fields.get(name);
            return (value == null) ? defvalue : value;
        } catch (ClassCastException e) {
            log.warning("Can't cast to char.", "name", name, "value", _fields.get(name), e);
            return defvalue;
        }
    }

    @Override
    public double read (String name, double defvalue)
        throws IOException
    {
        try {
            Number value = (Number)_fields.get(name);
            return (value == null) ? defvalue : value.doubleValue();
        } catch (ClassCastException e) {
            log.warning("Can't cast to double.", "name", name, "value", _fields.get(name), e);
            return defvalue;
        }
    }

    @Override
    public float read (String name, float defvalue)
        throws IOException
    {
        try {
            Number value = (Number)_fields.get(name);
            return (value == null) ? defvalue : value.floatValue();
        } catch (ClassCastException e) {
            log.warning("Can't cast to float.", "name", name, "value", _fields.get(name), e);
            return defvalue;
        }
    }

    @Override
    public int read (String name, int defvalue)
        throws IOException
    {
        try {
            Number value = (Number)_fields.get(name);
            return (value == null) ? defvalue : value.intValue();
        } catch (ClassCastException e) {
            log.warning("Can't cast to int.", "name", name, "value", _fields.get(name), e);
            return defvalue;
        }
    }

    @Override
    public long read (String name, long defvalue)
        throws IOException
    {
        try {
            Number value = (Number)_fields.get(name);
            return (value == null) ? defvalue : value.longValue();
        } catch (ClassCastException e) {
            log.warning("Can't cast to long.", "name", name, "value", _fields.get(name), e);
            return defvalue;
        }
    }

    @Override
    public short read (String name, short defvalue)
        throws IOException
    {
        try {
            Number value = (Number)_fields.get(name);
            return (value == null) ? defvalue : value.shortValue();
        } catch (ClassCastException e) {
            log.warning("Can't cast to short.", "name", name, "value", _fields.get(name), e);
            return defvalue;
        }
    }

    @Override
    public <T> T read (String name, T defvalue, Class<T> clazz)
        throws IOException
    {
        Object value = _fields.get(name);
        if (value == null) {
            if (_fields.containsKey(name)) {
                return null; // it's a legitimate null
            }
            // otherwise it wasn't in the map and we'll return defvalue

        } else if (clazz.isInstance(value)) {
            @SuppressWarnings("unchecked") // we actually just checked it!
            T tvalue = (T)value;
            return tvalue;

        } else {
            log.warning("Read value is not the correct type.",
                    "name", name, "expectedType", clazz.getName(),
                    "actualType", value.getClass().getName());
        }
        return defvalue;
    }

    @Override
    public void close ()
        throws IOException
    {
        // close the underlying stream
        _in.close();
    }

    /**
     * Reads in an object of the specified class.
     */
    protected Object read (Class<?> clazz)
        throws IOException
    {
        return read(getClassWrapper(clazz));
    }

    /**
     * Reads in an object of the specified class.
     */
    protected Object read (ClassWrapper clazz)
        throws IOException
    {
        // read primitive values directly
        if (clazz.isPrimitive()) {
            return readValue(clazz, -1);
        }

        // read in the id, see if we've seen it before
        int objectId = _objectIdReader.read();
        Object value = _objects.get(objectId);
        if (value != null) {
            return (value == NULL) ? null : value;
        }
        // if not, read the value
        return readValue(clazz, objectId);
    }

    /**
     * Reads in an object of the specified class.
     */
    protected Object readValue (ClassWrapper clazz, int objectId)
        throws IOException
    {
        // read in the class unless we can determine it implicitly
        ClassWrapper cclazz = clazz;
        if (!clazz.isFinal()) {
            cclazz = readClass();
        }
        // see if we can stream the value directly
        Class<?> wclazz = cclazz.getWrappedClass();
        if (wclazz != null) {
            @SuppressWarnings("unchecked")
            Streamer<Object> streamer = (Streamer<Object>)Streamer.getStreamer(wclazz);
            if (streamer != null) {
                Object value = null;
                try {
                    value = streamer.read(_in);
                } catch (ClassNotFoundException e) {
                    log.warning("Class not found.", e);
                }
                if (value != null && objectId != -1) {
                    _objects.put(objectId, value);
                }
                return value;
            }
        }
        // otherwise, create and populate the object
        Object value = null;
        int length = 0;
        boolean wasRead = false;
        if (cclazz.isArray()) {
            length = _objectIdReader.readLength();
            if (wclazz != null) {
                value = Array.newInstance(wclazz.getComponentType(), length);
            }
        } else {
            Object outer = cclazz.isInner() ? read(_objectClass) : null;
            if (wclazz != null) {
                if (wclazz == ImmutableList.class) {
                    value = ImmutableList.copyOf(readEntries(Lists.newArrayList()));
                    wasRead = true;

                 } else if (wclazz == ImmutableSet.class) {
                    value = ImmutableSet.copyOf(readEntries(Lists.newArrayList()));
                    wasRead = true;

                } else if (wclazz == ImmutableMap.class) {
                    value = ImmutableMap.copyOf(readEntries(Maps.newHashMap()));
                    wasRead = true;

                } else if (wclazz == ImmutableMultiset.class) {
                    value = ImmutableMultiset.copyOf(readEntries(HashMultiset.create()));
                    wasRead = true;

                } else if (EnumSet.class.isAssignableFrom(wclazz)) {
                    @SuppressWarnings("unchecked") Class<Exporter.DummyEnum> eclazz =
                        (Class<Exporter.DummyEnum>)readClass().getWrappedClass();
                    value = EnumSet.noneOf(eclazz);

                } else {
                    value = ReflectionUtil.newInstance(wclazz, outer);
                }
            }
        }
        _objects.put(objectId, (value == null) ? NULL : value);
        if (wasRead) {
            return value;
        }
        if (cclazz.isArray()) {
            readEntries(value == null ? new Object[length] : (Object[])value,
                cclazz.getComponentType());
        } else if (cclazz.isCollection()) {
            if (cclazz.isMultiset()) {
                @SuppressWarnings("unchecked") Multiset<Object> multiset =
                    (value == null) ? HashMultiset.<Object>create() : (Multiset<Object>)value;
                readEntries(multiset);
            } else {
                @SuppressWarnings("unchecked") Collection<Object> collection =
                    (value == null) ? new ArrayList<Object>() : (Collection<Object>)value;
                readEntries(collection);
            }
        } else if (cclazz.isMap()) {
            @SuppressWarnings("unchecked") Map<Object, Object> map =
                (value == null) ? new HashMap<Object, Object>() : (Map<Object, Object>)value;
            readEntries(map);
        } else {
            ClassData cdata = _classData.get(cclazz);
            if (cdata == null) {
                _classData.put(cclazz, cdata = new ClassData());
            }
            _fields = cdata.readFields();
            if (value instanceof Exportable) {
                readFields((Exportable)value);
            }
            _fields = null;
        }
        return value;
    }

    /**
     * Reads in a class reference.  While it's possibly simply to write the class reference out
     * as a normal object, we keep a separate id space for object/field classes in order to keep
     * the ids small.
     */
    protected ClassWrapper readClass ()
        throws IOException
    {
        // read in the id, see if we've seen it before
        int classId = _classIdReader.read();
        ClassWrapper clazz = _classes.get(classId);
        if (clazz != null) {
            return clazz;
        }
        // if not, read and map the value
        _classes.put(classId, clazz = getClassWrapper(_in.readUTF(), _in.readByte()));
        return clazz;
    }

    /**
     * Populates the supplied array with the entries under the current element.
     */
    protected void readEntries (Object[] array, ClassWrapper cclazz)
        throws IOException
    {
        for (int ii = 0; ii < array.length; ii++) {
            array[ii] = read(cclazz);
        }
    }

    /**
     * Populates the supplied collection with the entries under the current element.
     *
     * @return a reference to the collection passed, for chaining.
     */
    protected Collection<Object> readEntries (Collection<Object> collection)
        throws IOException
    {
        for (int ii = 0, nn = _objectIdReader.readLength(); ii < nn; ii++) {
            collection.add(read(_objectClass));
        }
        return collection;
    }

    /**
     * Populates the supplied multiset with the entries under the current element.
     *
     * @return a reference to the multiset passed, for chaining.
     */
    protected Multiset<Object> readEntries (Multiset<Object> multiset)
        throws IOException
    {
        for (int ii = 0, nn = _objectIdReader.readLength(); ii < nn; ii++) {
            multiset.add(read(_objectClass), _objectIdReader.readLength());
        }
        return multiset;
    }

    /**
     * Populates the supplied map with the entries under the current element.
     *
     * @return a reference to the map passed, for chaining.
     */
    protected Map<Object, Object> readEntries (Map<Object, Object> map)
        throws IOException
    {
        for (int ii = 0, nn = _objectIdReader.readLength(); ii < nn; ii++) {
            map.put(read(_objectClass), read(_objectClass));
        }
        return map;
    }

    /**
     * Returns a shared class wrapper instance.
     */
    protected ClassWrapper getClassWrapper (String name, byte flags)
    {
        ClassWrapper wrapper = _wrappersByName.get(name);
        if (wrapper == null) {
            _wrappersByName.put(name, wrapper = new ClassWrapper(this, name, flags));
            Class<?> clazz = wrapper.getWrappedClass();
            if (clazz != null) {
                _wrappersByClass.put(clazz, wrapper);
            }
        }
        return wrapper;
    }

    /**
     * Returns a shared class wrapper instance.
     */
    protected ClassWrapper getClassWrapper (Class<?> clazz)
    {
        ClassWrapper wrapper = _wrappersByClass.get(clazz);
        if (wrapper == null) {
            _wrappersByClass.put(clazz, wrapper = new ClassWrapper(this, clazz));
            _wrappersByName.put(clazz.getName(), wrapper);
        }
        return wrapper;
    }

    /**
     * Contains information on a class in the stream, which may or may not be resolvable.
     */
    protected static class ClassWrapper
    {
        public ClassWrapper (BinaryImporter importer, String name, byte flags)
        {
            _name = name;
            if (name.charAt(0) == '[') {
                _flags = BinaryExporter.FINAL_CLASS_FLAG;
                String cname = name.substring(1);
                char type = cname.charAt(0);
                if (type == '[') { // sub-array
                    _componentType = importer.getClassWrapper(cname, flags);
                } else if (type == 'L') { // object class or interface
                    _componentType =
                        importer.getClassWrapper(cname.substring(1, cname.length()-1), flags);
                } else { // primitive array
                    try {
                        _clazz = Class.forName(name);
                    } catch (ClassNotFoundException e) { }
                    _componentType = importer.getClassWrapper(_clazz.getComponentType());
                    return;
                }
                if (_componentType.getWrappedClass() == null) {
                    return; // don't bother trying to resolve the array class
                }
            } else {
                _flags = flags;
            }
            try {
                _clazz = Class.forName(name);
            } catch (ClassNotFoundException e) {
                log.warning("Couldn't find class to import [name=" + name + "].");
            }
        }

        public ClassWrapper (BinaryImporter importer, Class<?> clazz)
        {
            _name = clazz.getName();
            _flags = BinaryExporter.getFlags(clazz);
            if (clazz.isArray()) {
                _componentType = importer.getClassWrapper(clazz.getComponentType());
            }
            _clazz = clazz;
        }

//        // For static mappings
//        protected ClassWrapper (String name, Class<?> clazz, ClassWrapper componentType)
//        {
//            _name = name;
//            _flags = BinaryExporter.getFlags(clazz);
//            _componentType = componentType;
//            _clazz = clazz;
//        }

        /**
         * Returns the name of the class.
         */
        public String getName ()
        {
            return _name;
        }

        /**
         * Determines whether the wrapped class is final.
         */
        public boolean isFinal ()
        {
            return hasFlags(BinaryExporter.FINAL_CLASS_FLAG);
        }

        /**
         * Determines whether the wrapped class is a non-static inner class.
         */
        public boolean isInner ()
        {
            return hasFlags(BinaryExporter.INNER_CLASS_FLAG);
        }

        /**
         * Determines whether the wrapped class is a collection class.
         */
        public boolean isCollection ()
        {
            return hasFlags(BinaryExporter.COLLECTION_CLASS_FLAG);
        }

        /**
         * Determines whether the wrapped class is a multiset class.
         */
        public boolean isMultiset ()
        {
            return hasFlags(
                (byte)(BinaryExporter.COLLECTION_CLASS_FLAG | BinaryExporter.MULTI_FLAG));
        }

        /**
         * Determines whether the wrapped class is a map class.
         */
        public boolean isMap ()
        {
            // require that the MULTI_FLAG is *not* set
            return hasFlags(
                (byte)(BinaryExporter.MAP_CLASS_FLAG | BinaryExporter.MULTI_FLAG),
                BinaryExporter.MAP_CLASS_FLAG);
        }

//        /**
//         * Determines whether the wrapped class is a multimap.
//         */
//        public boolean isMultimap ()
//        {
//            return hasFlags(
//                BinaryExporter.MAP_CLASS_FLAG | BinaryExporter.MULTICOLLECTION_CLASS_FLAG);
//        }

        /**
         * Determines whether the wrapped class is an array class.
         */
        public boolean isArray ()
        {
            return (_componentType != null);
        }

        /**
         * Determines whether the wrapped class is a primitive class.
         */
        public boolean isPrimitive ()
        {
            return (_clazz != null && _clazz.isPrimitive());
        }

        /**
         * Returns the wrapper of the component type, if this is an array class.
         */
        public ClassWrapper getComponentType ()
        {
            return _componentType;
        }

        /**
         * Returns the wrapped class, if it could be resolved.
         */
        public Class<?> getWrappedClass ()
        {
            return _clazz;
        }

        @Override
        public int hashCode ()
        {
            return _name.hashCode();
        }

        @Override
        public boolean equals (Object other)
        {
            return ((ClassWrapper)other)._name.equals(_name);
        }

        /**
         * Return true if the specified flags are all set.
         */
        protected boolean hasFlags (byte flags)
        {
            return hasFlags(flags, flags);
        }

        /**
         * Return true if the flags match the specified flags exactly for the specified mask.
         */
        protected boolean hasFlags (byte mask, byte flags)
        {
            return (_flags & mask) == flags;
        }

        /** The name of the class. */
        protected String _name;

        /** The class flags. */
        protected byte _flags;

        /** The component type wrapper. */
        protected ClassWrapper _componentType;

        /** The class reference, if it could be resolved. */
        protected Class<?> _clazz;
    }

    /**
     * Contains information on an exportable class.
     */
    protected class ClassData
    {
        /**
         * Reads the field values in the supplied map.
         */
        public Map<String, Object> readFields ()
            throws IOException
        {
            int size = _fieldIdReader.readLength();
            Map<String, Object> fields = new HashMap<String, Object>(size);
            for (int ii = 0; ii < size; ii++) {
                readField(fields);
            }
            return fields;
        }

        /**
         * Reads in a single field value.
         */
        protected void readField (Map<String, Object> fields)
            throws IOException
        {
            int fieldId = _fieldIdReader.read();
            FieldData fieldData = _fieldData.get(fieldId);
            if (fieldData == null) {
                String name = (String)read(_stringClass);
                ClassWrapper clazz = readClass();
                _fieldData.put(fieldId, fieldData = new FieldData(name, clazz));
            }
            fields.put(fieldData.name, read(fieldData.clazz));
        }

        /** Maps field ids to name/class pairs. */
        protected HashIntMap<FieldData> _fieldData = new HashIntMap<FieldData>();

        /** Used to read field ids. */
        protected IdReader _fieldIdReader = _idReaderSupplier.get();

        /**
         * Holds data on incoming fields.
         */
        protected static class FieldData
        {
            /** The name of the field. */
            public final String name;

            /** The class wrapper for this field. */
            public final ClassWrapper clazz;

            /**
             * Constructor.
             */
            public FieldData (String name, ClassWrapper clazz)
            {
                this.name = name;
                this.clazz = clazz;
            }
        }
    }

    /**
     * Reads field, object, or class ids off the stream.
     */
    interface IdReader
    {
        /**
         * Read the next id on the stream.
         */
        public int read ()
            throws IOException;

        /**
         * Read the next length on the stream.
         * (This can technically use the nearest available IdReader as there is no "state" in
         * any version.).
         */
        public int readLength ()
            throws IOException;
    }

    /**
     * Ids and lengths are encoded as varints.
     */
    protected class VarIntReader
        implements IdReader
    {
        @Override
        public int read ()
            throws IOException
        {
            return Streams.readVarInt(_in);
        }

        @Override
        public int readLength ()
            throws IOException
        {
            return Streams.readVarInt(_in);
        }
    }

    /**
     * Ids are encoded as "varints", lengths are always ints.
     */
    protected class IntermediateIdReader extends VarIntReader
    {
        /** The BinaryExporter version number that we read. */
        public static final int VERSION = 0x1001;

        @Override
        public int readLength ()
            throws IOException
        {
            return _in.readInt(); // old way
        }
    }

    /**
     * Compatability with classic export id style.
     */
    protected class ClassicIdReader
        implements IdReader
    {
        /** The BinaryExporter version number that we read. */
        public static final int VERSION = 0x1000;

        @Override
        public int read ()
            throws IOException
        {
            int id;
            if (_highest < 255) {
                id = _in.readUnsignedByte();
            } else if (_highest < 65535) {
                id = _in.readUnsignedShort();
            } else {
                id = _in.readInt();
            }
            _highest = Math.max(_highest, id);
            return id;
        }

        @Override
        public int readLength ()
            throws IOException
        {
            return _in.readInt();
        }

        /** The highest value written so far. */
        protected int _highest;
    }

    /** The underlying input stream. */
    protected InputStream _base;

    /** The stream that we use for reading data. */
    protected DataInputStream _in;

    /** Maps ids to objects read.  A null value indicates that the stream has not yet been
     * initialized. */
    protected HashIntMap<Object> _objects;

    /** Supplies us with IdReader instances. */
    protected Supplier<IdReader> _idReaderSupplier;

    /** Used to read object ids. */
    protected IdReader _objectIdReader;

    /** Field values associated with the current object. */
    protected Map<String, Object> _fields;

    /** Maps class names to wrapper objects (for classes identified in the stream). */
    protected Map<String, ClassWrapper> _wrappersByName = Maps.newHashMap(/*_staticMappings*/);

    /** Maps class objects to wrapper objects (for classes identified by reference). */
    protected Map<Class<?>, ClassWrapper> _wrappersByClass = Maps.newHashMap();

    /** Maps ids to classes read. */
    protected HashIntMap<ClassWrapper> _classes = new HashIntMap<ClassWrapper>();

    /** The wrapper for the object class. */
    protected ClassWrapper _objectClass;

    /** The wrapper for the String class. */
    protected ClassWrapper _stringClass;

    /** Used to read class ids. */
    protected IdReader _classIdReader;

    /** Class<?> data. */
    protected Map<ClassWrapper, ClassData> _classData = Maps.newHashMap();

    /** Signifies a null entry in the object map. */
    protected static final Object NULL = new Object();

//    /** Static mappings. */
//    protected static Map<String, ClassWrapper> _staticMappings = Maps.newHashMap();
}
