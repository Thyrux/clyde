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

import java.lang.reflect.Array;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import com.threerings.util.ReflectionUtil;

import static com.threerings.export.Log.*;

/**
 * Imports from the XML format generated by {@link XMLExporter}.
 */
public class XMLImporter extends Importer
{
    /**
     * Creates an importer to read from the specified stream.
     */
    public XMLImporter (InputStream in)
    {
        _in = in;
    }

    @Override
    public Object readObject ()
        throws IOException
    {
        Node first;
        if (_document == null) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            try {
                DocumentBuilder builder = factory.newDocumentBuilder();
                _document = builder.parse(_in);
            } catch (Exception e) {
                throw new IOException("Failed to parse input [error=" + e + "].");
            }
            Element top = _document.getDocumentElement();
            if (!top.getTagName().equals("java")) {
                throw new IOException("Invalid top-level element [name=" +
                    top.getTagName() + "].");
            }
            String vstr = top.getAttribute("version");
            if (!vstr.equals(XMLExporter.VERSION)) {
                throw new IOException("Invalid version [version=" + vstr + ", expected=" +
                    XMLExporter.VERSION + "].");
            }
            String cstr = top.getAttribute("class");
            if (!cstr.equals(getClass().getName())) {
                throw new IOException("Invalid importer class [class=" + cstr + ", expected=" +
                    getClass().getName() + "].");
            }
            first = top.getFirstChild();
        } else {
            first = (_element == null) ? null : _element.getNextSibling();
        }
        if ((_element = findElement(first, "object")) == null) {
            throw new EOFException();
        }
        return read(_element, Object.class);
    }

    @Override
    public boolean read (String name, boolean defvalue)
        throws IOException
    {
        String value = getValue(name);
        return (value == null) ? defvalue : Boolean.parseBoolean(value);
    }

    @Override
    public byte read (String name, byte defvalue)
        throws IOException
    {
        String value = getValue(name);
        try {
            return (value == null) ? defvalue : Byte.parseByte(value);
        } catch (NumberFormatException e) {
            log.warning("Couldn't parse value as byte [value=" + value + "].", e);
            return defvalue;
        }
    }

    @Override
    public char read (String name, char defvalue)
        throws IOException
    {
        String value = getValue(name);
        return (value == null) ? defvalue : value.charAt(0);
    }

    @Override
    public double read (String name, double defvalue)
        throws IOException
    {
        String value = getValue(name);
        try {
            return (value == null) ? defvalue : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            log.warning("Couldn't parse value as double [value=" + value + "].", e);
            return defvalue;
        }
    }

    @Override
    public float read (String name, float defvalue)
        throws IOException
    {
        String value = getValue(name);
        try {
            return (value == null) ? defvalue : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            log.warning("Couldn't parse value as float [value=" + value + "].", e);
            return defvalue;
        }
    }

    @Override
    public int read (String name, int defvalue)
        throws IOException
    {
        String value = getValue(name);
        try {
            return (value == null) ? defvalue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warning("Couldn't parse value as int [value=" + value + "].", e);
            return defvalue;
        }
    }

    @Override
    public long read (String name, long defvalue)
        throws IOException
    {
        String value = getValue(name);
        try {
            return (value == null) ? defvalue : Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warning("Couldn't parse value as long [value=" + value + "].", e);
            return defvalue;
        }
    }

    @Override
    public short read (String name, short defvalue)
        throws IOException
    {
        String value = getValue(name);
        try {
            return (value == null) ? defvalue : Short.parseShort(value);
        } catch (NumberFormatException e) {
            log.warning("Couldn't parse value as short [value=" + value + "].", e);
            return defvalue;
        }
    }

    @Override
    public <T> T read (String name, T defvalue, Class<T> clazz)
        throws IOException
    {
        Element child = findElement(_element.getFirstChild(), name);
        if (child == null) {
            return defvalue;
        }
        return clazz.cast(read(child, clazz));
    }

    @Override
    public void close ()
        throws IOException
    {
        _in.close();
    }

    /**
     * Reads an object of the supplied type from the given element.
     */
    protected Object read (Element element, Class<?> clazz)
        throws IOException
    {
        String ref = element.getAttribute("ref");
        String rdepth = element.getAttribute("rdepth");
        if (rdepth.length() > 0) {
            return _depths.get(rdepth);
        } else if (ref.length() > 0) {
            return _objects.get(ref);
        } else if (element.getFirstChild() == null) {
            return null;
        } else {
            return readValue(element, clazz);
        }
    }

    /**
     * Returns the named class, or null if not found.
     */
    protected Class<?> getClassByName (String cstr, Class<?> defval)
    {
        if (cstr.length() > 0) {
            try {
                return Class.forName(cstr);
            } catch (ClassNotFoundException e) {
                log.warning("Class not found.", e);
                return null;
            }
        }
        return defval;
    }

    /**
     * Reads an object value of the specified class from the given element.
     */
    protected Object readValue (Element element, Class<?> clazz)
        throws IOException
    {
        // see if we can read the value from a string
        String id = element.getAttribute("id");
        String depth = element.getAttribute("depth");
        Class<?> cclazz = getClassByName(element.getAttribute("class"), clazz);
        Stringifier stringifier = Stringifier.getStringifier(cclazz);
        if (stringifier != null) {
            String string = element.getTextContent();
            Object value = null;
            try {
                if ((value = stringifier.fromString(string)) == null) {
                    log.warning("Failed to parse string.", "string", string, "class", cclazz);
                }
            } catch (Exception e) {
                log.warning("Failed to parse string.", "string", string, "class", cclazz, e);
            }
            if (depth.length() > 0 && value != null) {
                putObjectDepth(depth, value);
            }
            if (id.length() > 0 && value != null) {
                putObject(id, value);
            }
            return value;
        }
        // otherwise, process the element
        Element oelement = _element;
        _element = element;
        try {
            Object value;
            boolean wasRead = false;
            if (cclazz.isArray()) {
                value = Array.newInstance(cclazz.getComponentType(), countEntries());

            } else if (cclazz == ImmutableList.class) {
                value = ImmutableList.copyOf(readEntries(Lists.newArrayList()));
                wasRead = true;

            } else if (cclazz == ImmutableSet.class) {
                value = ImmutableSet.copyOf(readEntries(Lists.newArrayList()));
                wasRead = true;

            } else if (cclazz == ImmutableMap.class) {
                value = ImmutableMap.copyOf(readEntries(Maps.newHashMap()));
                wasRead = true;

            } else if (EnumSet.class.isAssignableFrom(cclazz)) {
                @SuppressWarnings("unchecked") Class<? extends Enum> eclazz =
                    (Class<? extends Enum>)getClassByName(element.getAttribute("eclass"), null);
                @SuppressWarnings("unchecked") EnumSet<?> set = EnumSet.noneOf(eclazz);
                value = set;

            } else {
                value = ReflectionUtil.newInstance(cclazz,
                    ReflectionUtil.isInner(cclazz) ? read("outer", null, Object.class) : null);
            }
            if (depth.length() > 0 && value != null) {
                putObjectDepth(depth, value);
            }
            if (id.length() > 0) {
                putObject(id, value);
            }
            if (wasRead) {
                return value;
            }
            if (value instanceof Exportable) {
                readFields((Exportable)value);
            } else if (value instanceof Object[]) {
                readEntries((Object[])value, cclazz.getComponentType());
            } else if (value instanceof Collection) {
                @SuppressWarnings("unchecked") Collection<Object> collection =
                    (Collection<Object>)value;
                readEntries(collection);
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked") Map<Object, Object> map =
                    (Map<Object, Object>)value;
                readEntries(map);
            }
            return value;

        } finally {
            _element = oelement;
            if (depth.length() > 0) {
                _depths.remove(depth);
            }
        }
    }

    /**
     * Stores an object in the map, logging a warning if we overwrite an existing entry.
     */
    protected void putObject (String id, Object value)
    {
        Object ovalue = _objects.put(id, value);
        if (ovalue != null) {
            log.warning("Duplicate id detected.", "id", id, "ovalue", ovalue, "nvalue", value);
        }
    }

    /**
     * Stores an object in the map, logging a warning if we overwrite an existing entry.
     */
    protected void putObjectDepth (String depth, Object value)
    {
        Object ovalue = _depths.put(depth, value);
        if (ovalue != null) {
            log.warning("Duplicate depth detected.", "depth", depth, "ovalue", ovalue, "nvalue", value);
        }
    }

    /**
     * Returns the number of entries under the current element.
     */
    protected int countEntries ()
    {
        int count = 0;
        for (Node node = _element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && node.getNodeName().equals("entry")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Populates the supplied array with the entries under the current element.
     */
    protected void readEntries (Object[] array, Class<?> cclazz)
        throws IOException
    {
        int idx = 0;
        for (Node node = _element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && node.getNodeName().equals("entry")) {
                array[idx++] = read((Element)node, cclazz);
            }
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
        for (Node node = _element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && node.getNodeName().equals("entry")) {
                collection.add(read((Element)node, Object.class));
            }
        }
        return collection;
    }

    /**
     * Populates the supplied map with the entries under the current element.
     *
     * @return a reference to the map passed, for chaining.
     */
    protected Map<Object, Object> readEntries (Map<Object, Object> map)
        throws IOException
    {
        for (Node node = _element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && node.getNodeName().equals("key")) {
                Object key = read((Element)node, Object.class);
                for (node = node.getNextSibling(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element && node.getNodeName().equals("value")) {
                        map.put(key, read((Element)node, Object.class));
                        break;
                    }
                }
            }
        }
        return map;
    }

    /**
     * For simple text fields, retrieves the value from a child element.
     */
    protected String getValue (String name)
    {
        Element child = findElement(_element.getFirstChild(), name);
        if (child == null) {
            return null;
        }
        for (Node node = child.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Text) {
                return node.getNodeValue();
            }
        }
        return null;
    }

    /**
     * Finds the first element in the sibling chain with the given name.
     */
    protected static Element findElement (Node first, String name)
    {
        for (Node node = first; node != null; node = node.getNextSibling()) {
            if (node instanceof Element && node.getNodeName().equals(name)) {
                return (Element)node;
            }
        }
        return null;
    }

    /** The source stream. */
    protected InputStream _in;

    /** The parsed XML document. */
    protected Document _document;

    /** The element associated with the current object. */
    protected Element _element;

    /** Mappings from ids to referenced objects. */
    protected HashMap<String, Object> _objects = new HashMap<String, Object>();
    protected HashMap<String, Object> _depths = new HashMap<String, Object>();
}
