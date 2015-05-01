/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.module;

import java.lang.module.ModuleDescriptor.Requires;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jdk.internal.module.Hasher.DependencyHashes;

/**
 * The resolver used by {@link Configuration#resolve} and {@link Configuration#bind}.
 */

final class Resolver {

    /**
     * The result of resolution or binding.
     */
    final class Resolution {

        // the set of module descriptors
        private final Set<ModuleDescriptor> selected;

        // maps name to module artifact for modules in this resolution
        private final Map<String, ModuleArtifact> nameToArtifact;

        // the readbility graph
        private final Map<ModuleDescriptor, Set<ModuleDescriptor>> graph;

        Resolution(Set<ModuleDescriptor> selected,
                   Map<String, ModuleArtifact> nameToArtifact,
                   Map<ModuleDescriptor, Set<ModuleDescriptor>> graph)
        {
            this.selected = Collections.unmodifiableSet(selected);
            this.nameToArtifact = Collections.unmodifiableMap(nameToArtifact);
            this.graph = graph; // no need to make defensive copy
        }

        Set<ModuleDescriptor> selected() {
            return selected;
        }

        ModuleArtifact findArtifact(String name) {
            return nameToArtifact.get(name);
        }

        Set<ModuleDescriptor> readDependences(ModuleDescriptor descriptor) {
            Set<ModuleDescriptor> reads = graph.get(descriptor);
            if (reads == null) {
                return null;
            } else {
                return Collections.unmodifiableSet(reads);
            }
        }

        /**
         * Returns a new Resolution that this is this Resolution augmented with
         * modules (located via the module artifact finders) that are induced
         * by service-use relationships.
         */
        Resolution bind() {
            return Resolver.this.bind();
        }

    }


    private final ModuleArtifactFinder beforeFinder;
    private final Layer layer;
    private final ModuleArtifactFinder afterFinder;

    // the set of module descriptors, added to at each iteration of resolve
    private final Set<ModuleDescriptor> selected = new HashSet<>();

    // map of module names to artifacts
    private final Map<String, ModuleArtifact> nameToArtifact = new HashMap<>();


    private Resolver(ModuleArtifactFinder beforeFinder,
                     Layer layer,
                     ModuleArtifactFinder afterFinder)
    {
        this.beforeFinder = beforeFinder;
        this.layer = layer;
        this.afterFinder = afterFinder;
    }

    /**
     * Resolves the given named modules. Module dependences are resolved by
     * locating them (in order) using the given {@code beforeFinder}, {@code
     * layer}, and {@code afterFinder}.
     *
     * @throws ResolutionException
     */
    static Resolution resolve(ModuleArtifactFinder beforeFinder,
                              Layer layer,
                              ModuleArtifactFinder afterFinder,
                              Collection<String> roots)
    {
        Resolver resolver = new Resolver(beforeFinder, layer, afterFinder);
        return resolver.resolve(roots);
    }

    /**
     * Resolve the given collection of modules (by name).
     */
    private Resolution resolve(Collection<String> roots) {

        // create the visit stack to get us started
        Deque<ModuleDescriptor> q = new ArrayDeque<>();
        for (String root : roots) {

            ModuleArtifact artifact = beforeFinder.find(root);
            if (artifact == null) {
                // ## Does it make sense to attempt to locate root modules with
                //    a finder other than the beforeFinder?
                artifact = afterFinder.find(root);
                if (artifact == null) {
                    fail("Module %s does not exist", root);
                }
            }

            trace("Module %s located (%s)", root, artifact.location());

            nameToArtifact.put(root, artifact);
            q.push(artifact.descriptor());
        }

        resolve(q);

        checkHashes();

        Map<ModuleDescriptor, Set<ModuleDescriptor>> graph = makeGraph();

        return new Resolution(selected, nameToArtifact, graph);
    }

    /**
     * Poll the given {@code Deque} for modules to resolve. The {@code selected}
     * set is updated as modules are processed. On completion the {@code Deque}
     * will be empty.
     *
     * @return The set of module (descriptors) selected by this invocation of
     *         resolve
     */
    private Set<ModuleDescriptor> resolve(Deque<ModuleDescriptor> q) {
        Set<ModuleDescriptor> newlySelected = new HashSet<>();

        while (!q.isEmpty()) {
            ModuleDescriptor descriptor = q.poll();
            assert nameToArtifact.containsKey(descriptor.name());
            selected.add(descriptor);

            // process dependences
            for (ModuleDescriptor.Requires requires: descriptor.requires()) {
                String dn = requires.name();

                // before finder
                ModuleArtifact artifact = beforeFinder.find(dn);

                // already defined to the runtime
                if (artifact == null && layer.findModule(dn) != null) {
                    continue;
                }

                // after finder
                if (artifact == null) {
                    artifact = afterFinder.find(dn);
                }

                // not found
                if (artifact == null) {
                    fail("%s requires unknown module %s", descriptor.name(), dn);
                }

                // check if module descriptor has already been seen
                ModuleDescriptor other = artifact.descriptor();
                if (!selected.contains(other) && !newlySelected.contains(other)) {

                    trace("Module %s located (%s), required by %s",
                            dn, artifact.location(), descriptor.name());

                    newlySelected.add(other);
                    nameToArtifact.put(dn, artifact);
                    q.offer(other);
                }
            }
        }

        return newlySelected;
    }

    /**
     * Updates the Resolver with modules (located via the module artifact finders)
     * that are induced by service-use relationships.
     */
    private Resolution bind() {

        // Scan the finders for all available service provider modules. As java.base
        // uses services then all finders will need to be scanned anyway.
        Map<String, Set<ModuleArtifact>> availableProviders = new HashMap<>();
        for (ModuleArtifact artifact : beforeFinder.allModules()) {
            ModuleDescriptor descriptor = artifact.descriptor();
            if (!descriptor.provides().isEmpty()) {
                descriptor.provides().keySet().forEach(s ->
                    availableProviders.computeIfAbsent(s, k -> new HashSet<>()).add(artifact));
            }
        }
        for (ModuleArtifact artifact : afterFinder.allModules()) {
            ModuleDescriptor descriptor = artifact.descriptor();
            // the parent layer may hide service providers from afterFinder
            if (!descriptor.provides().isEmpty() && layer.findModule(descriptor.name()) == null) {
                descriptor.provides().keySet().forEach(s ->
                    availableProviders.computeIfAbsent(s, k -> new HashSet<>()).add(artifact));
            }
        }


        // create the visit stack
        Deque<ModuleDescriptor> q = new ArrayDeque<>();

        // the initial set of modules that may use services
        Set<ModuleDescriptor> candidateConsumers;
        if (layer == null) {
            candidateConsumers = selected;
        } else {
            candidateConsumers = new HashSet<>();
            candidateConsumers.addAll(layer.allModuleDescriptors());
            candidateConsumers.addAll(selected);
        }

        // Where there is a consumer of a service then resolve all modules
        // that provide an implementation of that service
        // ### TBD to to record the service-use graph
        do {
            for (ModuleDescriptor descriptor : candidateConsumers) {
                if (!descriptor.uses().isEmpty()) {
                    for (String service : descriptor.uses()) {
                        Set<ModuleArtifact> artifacts = availableProviders.get(service);
                        if (artifacts != null) {
                            for (ModuleArtifact artifact : artifacts) {
                                ModuleDescriptor provider = artifact.descriptor();
                                if (!provider.equals(descriptor)) {
                                    if (!selected.contains(provider)) {
                                        trace("Module %s provides %s, used by %s",
                                                provider.name(), service, descriptor.name());
                                        nameToArtifact.put(provider.name(), artifact);
                                        q.push(provider);
                                    }
                                }
                            }
                        }
                    }
                }
            }

            candidateConsumers = resolve(q);

        } while (!candidateConsumers.isEmpty());


        checkHashes();

        Map<ModuleDescriptor, Set<ModuleDescriptor>> graph = makeGraph();

        return new Resolution(selected, nameToArtifact, graph);
    }

    /**
     * Computes and sets the readability graph for the modules in the given
     * Resolution object.
     *
     * The readability graph is created by propagating "requires" through the
     * "public requires" edges of the module dependence graph. So if the module
     * dependence graph has m1 requires m2 && m2 requires public m3 then the
     * resulting readability graph will contain m1 requires requires m2, m1
     * requires m3, and m2 requires m3.
     *
     * ###TBD Replace this will be more efficient implementation
     */
    private Map<ModuleDescriptor, Set<ModuleDescriptor>> makeGraph() {

        // name -> ModuleDescriptor lookup for newly selected modules
        Map<String, ModuleDescriptor> nameToModule = new HashMap<>();
        selected.forEach(d -> nameToModule.put(d.name(), d));

        // the "requires" graph starts as a module dependence graph and
        // is iteratively updated to be the readability graph
        Map<ModuleDescriptor, Set<ModuleDescriptor>> g1 = new HashMap<>();

        // the "requires public" graph, contains requires public edges only
        Map<ModuleDescriptor, Set<ModuleDescriptor>> g2 = new HashMap<>();

        // need "requires public" from the modules in parent layers as
        // there may be selected modules that have a dependence.
        Layer current = this.layer;
        while (current != null) {
            Configuration cf = current.configuration();
            if (cf != null) {
                for (ModuleDescriptor descriptor: cf.descriptors()) {
                    // requires
                    //Set<ModuleDescriptor> reads = cf.readDependences(descriptor);
                    //g1.put(descriptor, reads);

                    // requires public
                    g2.put(descriptor, new HashSet<>());
                    for (Requires d: descriptor.requires()) {
                        if (d.modifiers().contains(Requires.Modifier.PUBLIC)) {
                            String dn = d.name();
                            ModuleArtifact artifact = current.findArtifact(dn);
                            if (artifact == null)
                                throw new InternalError();
                            g2.get(descriptor).add(artifact.descriptor());
                        }
                    }
                }
            }
            current = current.parent();
        }

        // add the module dependence edges from the newly selected modules
        for (ModuleDescriptor m : selected) {
            g1.put(m, new HashSet<>());
            g2.put(m, new HashSet<>());
            for (Requires d: m.requires()) {
                String dn = d.name();
                ModuleDescriptor other = nameToModule.get(dn);
                if (other == null && layer != null)
                    other = layer.findArtifact(dn).descriptor();
                if (other == null)
                    throw new InternalError(dn + " not found??");

                // requires (and requires public)
                g1.get(m).add(other);

                // requires public only
                if (d.modifiers().contains(Requires.Modifier.PUBLIC)) {
                    g2.get(m).add(other);
                }
            }
        }

        // add to g1 until there are no more requires public to propagate
        boolean changed;
        Map<ModuleDescriptor, Set<ModuleDescriptor>> changes = new HashMap<>();
        do {
            changed = false;
            for (Map.Entry<ModuleDescriptor, Set<ModuleDescriptor>> entry: g1.entrySet()) {
                ModuleDescriptor m1 = entry.getKey();
                Set<ModuleDescriptor> m1_requires = entry.getValue();
                for (ModuleDescriptor m2: m1_requires) {
                    Set<ModuleDescriptor> m2_requires_public = g2.get(m2);
                    for (ModuleDescriptor m3: m2_requires_public) {
                        if (!m1_requires.contains(m3)) {
                            changes.computeIfAbsent(m1, k -> new HashSet<>()).add(m3);
                            changed = true;
                        }
                    }
                }
            }
            if (changed) {
                for (Map.Entry<ModuleDescriptor, Set<ModuleDescriptor>> entry: changes.entrySet()) {
                    ModuleDescriptor m1 = entry.getKey();
                    g1.get(m1).addAll(entry.getValue());
                }
                changes.clear();
            }

        } while (changed);

        // TBD - for each m1 -> m2 then need to check that m2 exports something to
        // m1. Need to watch out for the "hollowed-out case" where m2 is an aggregator.

        return g1;
    }

    /**
     * Checks the hashes in the extended module descriptor to ensure that they
     * match the hash of the dependency's module artifact.
     */
    private void checkHashes() {

        for (ModuleDescriptor descriptor : selected) {
            String mn = descriptor.name();

            // get map of module names to hash
            Optional<DependencyHashes> ohashes
                = nameToArtifact.get(mn).descriptor().hashes();
            if (!ohashes.isPresent())
                continue;
            DependencyHashes hashes = ohashes.get();

            // check dependences
            for (Requires md: descriptor.requires()) {
                String dn = md.name();
                String recordedHash = hashes.hashFor(dn);

                if (recordedHash != null) {
                    ModuleArtifact artifact = nameToArtifact.get(dn);
                    if (artifact == null)
                        artifact = layer.findArtifact(dn);
                    if (artifact == null)
                        throw new InternalError(dn + " not found");

                    String actualHash = artifact.computeHash(hashes.algorithm());
                    if (actualHash == null)
                        fail("Unable to compute the hash of module %s", dn);

                    if (!recordedHash.equals(actualHash)) {
                        fail("Hash of %s (%s) differs to expected hash (%s)",
                                dn, actualHash, recordedHash);
                    }
                }
            }
        }

    }


    static final boolean debug = false;

    private static void trace(String fmt, Object ... args) {
        if (debug) {
            System.out.format(fmt, args);
            System.out.println();
        }
    }

    private static void fail(String fmt, Object ... args) {
        throw new ResolutionException(fmt, args);
    }
}
