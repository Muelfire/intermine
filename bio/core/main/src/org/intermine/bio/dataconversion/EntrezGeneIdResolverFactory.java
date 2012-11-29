package org.intermine.bio.dataconversion;

/*
 * Copyright (C) 2002-2012 FlyMine
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.intermine.util.PropertiesUtil;

/**
 * ID resolver for Entrez genes.
 *
 * @author Richard Smith
 * @author Fengyuan Hu
 */
public class EntrezGeneIdResolverFactory extends IdResolverFactory
{
    protected static final Logger LOG = Logger.getLogger(EntrezGeneIdResolverFactory.class);
    private final String propName = "resolver.entrez.file"; // set in .intermine/MINE.properties

    private static final String PROP_FILE = "entrezIdResolver_config.properties";
    private Map<String, String> config_xref = new HashMap<String, String>();
    private Map<String, String> config_prefix = new HashMap<String, String>();
    private Map<String, String> config_strains = new HashMap<String, String>();
    private static final Set<String> ignoredTaxonIds = new HashSet<String>();

    /**
     * Constructor read pid configuration
     */
    public EntrezGeneIdResolverFactory() {
        readConfig();
    }

    /**
     * Return an IdResolver by taxon id, if not already built then create it.
     * @return a specific IdResolver
     */
    public IdResolver getIdResolver(String taxonId) {
        if (taxonId == null) {
            return null;
        }
        return getIdResolver(taxonId, true);
    }

    /**
     * Return an IdResolver by a list of taxon id, if not already built then create it.
     * @return a specific IdResolver
     */
    public IdResolver getIdResolver(Set<String> taxonIds) {
        if (taxonIds == null | taxonIds.isEmpty()) {
            return null;
        }
        return getIdResolver(taxonIds, true);
    }

    /**
     * Return an IdResolver by taxon id, if not already built then create it.  If failOnError
     * set to false then swallow any exceptions and return null.  Allows code to
     * continue if no resolver can be set up.
     * @param failOnError if false swallow any exceptions and return null
     * @return a specific IdResolver
     */
    public IdResolver getIdResolver(String taxonId, boolean failOnError) {
        if (!caughtError) {
            try {
                createIdResolver(taxonId);
            } catch (Exception e) {
                this.caughtError = true;
                if (failOnError) {
                    throw new RuntimeException(e);
                }
            }
        }
        return resolver;
    }

    /**
     * Return an IdResolver by a list of taxon ids, if not already built then create it.
     * If failOnError set to false then swallow any exceptions and return null.  Allows code to
     * continue if no resolver can be set up.
     * @param failOnError if false swallow any exceptions and return null
     * @return a specific IdResolver
     */
    public IdResolver getIdResolver(Set<String> taxonIds, boolean failOnError) {
        if (!caughtError) {
            try {
                createIdResolver(taxonIds);
            } catch (Exception e) {
                this.caughtError = true;
                if (failOnError) {
                    throw new RuntimeException(e);
                }
            }
        }
        return resolver;
    }

    /**
     * Build an IdResolver from Entrez Gene gene_info file
     * @return an IdResolver for Entrez Gene
     * @throws IOException
     * @throws FileNotFoundException
     */
    protected void createIdResolver(String taxonId) {
        // Don't pass null to asList - java bug (SUN already fixed it???)
        if (taxonId == null) {
            createIdResolver(new HashSet<String>());
        } else {
            createIdResolver(new HashSet<String>(Arrays.asList(taxonId)));
        }
    }

    /**
     * Build an IdResolver from Entrez Gene gene_info file
     * @param taxonIds list of taxon IDs
     * @return an IdResolver for Entrez Gene
     */
    protected void createIdResolver(Set<String> taxonIds) {
        taxonIds.removeAll(ignoredTaxonIds);
        LOG.info("Ignore taxons: " + ignoredTaxonIds + ", remain taxons: " + taxonIds);

        if (resolver != null && resolver.hasTaxons(taxonIds)) {
            return;
        } else {
            if (resolver == null) {
                if (clsCol.size() > 1) {
                    resolver = new IdResolver();
                } else {
                    resolver = new IdResolver(clsCol.iterator().next());
                }
            }
        }

        try {
            boolean isCachedIdResolverRestored = restoreFromFile();
            if (!isCachedIdResolverRestored || (isCachedIdResolverRestored
                    && !resolver.hasTaxons(taxonIds))) {
                Properties props = PropertiesUtil.getProperties();
                String fileName = props.getProperty(propName);

                // File path not set in MINE.properties
                if (StringUtils.isBlank(fileName)) {
                    String message = "Entrez gene resolver has no file name specified, set "
                        + propName + " to the location of the gene_info file.";
                    LOG.warn(message);
                    return;
                }

                LOG.info("Creating id resolver from data file and caching it.");
                createFromFile(new BufferedReader(new FileReader(new File(fileName))), taxonIds);
                resolver.writeToFile(new File(ID_RESOLVER_CACHED_FILE_NAME));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void createFromFile(BufferedReader reader, Set<String> taxonIds) throws IOException {
        // in ncbi gene_info, some organisms use strain taxon id, e.g.yeast
        Map<String, String> newTaxonIds = getStrain(taxonIds);
        LOG.info("New taxons: " + newTaxonIds.keySet() + ", original taxons: "
                + newTaxonIds.values());

        NcbiGeneInfoParser parser = new NcbiGeneInfoParser(reader,
                new HashSet<String>(newTaxonIds.keySet()));
        Map<String, Set<GeneInfoRecord>> records = parser.getGeneInfoRecords();
        if (records == null) {
            throw new IllegalArgumentException("Failed to read any records from gene_info file.");
        }

        // Some species are not found in gene_info
        if (newTaxonIds.size() > records.size()) {
            Set<String> taxonIdsCopy = new HashSet<String>(newTaxonIds.keySet());
            taxonIdsCopy.removeAll(records.keySet());
            if (taxonIdsCopy.size() > 0) {
                LOG.warn("No records in gene_info file for species: "
                        + taxonIdsCopy);
            }
        }

        for (String newTaxon : records.keySet()) {
            // resolver still uses original taxon
            if (resolver.hasTaxon(newTaxonIds.get(newTaxon))) {
                continue;
            }
            Set<GeneInfoRecord> genes = records.get(newTaxon);
            // use original taxon id in resolver
            // no need to lookup strain in converter
            processGenes(newTaxonIds.get(newTaxon), genes);
        }
    }

    private void processGenes(String taxonId, Set<GeneInfoRecord> genes) {
        for (GeneInfoRecord record : genes) {
            String primaryIdentifier;
            String config = config_xref.get(taxonId); // the original taxon id, not strain
            if (record.xrefs.get(config) != null) {
                String prefix = config_prefix.get(taxonId); // eg. RGD:
                primaryIdentifier = record.xrefs.get(config).iterator().next();
                if (StringUtils.isNotEmpty(prefix)) {
                    primaryIdentifier = prefix + primaryIdentifier;
                }

            } else {
                primaryIdentifier = record.entrez;
            }

            resolver.addMainIds(taxonId, primaryIdentifier, record.getMainIds());
            resolver.addSynonyms(taxonId, primaryIdentifier,
                    flattenCollections(record.xrefs.values()));
            resolver.addSynonyms(taxonId, primaryIdentifier, record.synonyms);
        }
    }

    /**
     * Read pid configurations from entrezIdResolver_config.properties in resources dir
     */
    private void readConfig() {
        Properties entrezConfig = new Properties();
        try {
            entrezConfig.load(getClass().getClassLoader().getResourceAsStream(
                    PROP_FILE));
        } catch (IOException e) {
            throw new RuntimeException("I/O Problem loading properties '"
                    + PROP_FILE + "'", e);
        }

        for (Map.Entry<Object, Object> entry : entrezConfig.entrySet()) {
            if ("taxon.ignored".equals(entry.getKey())) {  // taxon to ignore
                if (entry.getValue() != null || !((String) entry.getValue()).trim().isEmpty()) {
                    String[] ignoredTaxons = ((String) entry.getValue()).trim().split("\\s*,\\s*");
                    ignoredTaxonIds.addAll(Arrays.asList(ignoredTaxons));
                }
            } else if (entry.getKey().toString().contains("strains")) { // use strain
                if (entry.getValue() != null || !((String) entry.getValue()).trim().isEmpty()) {
                    config_strains.put(
                            entry.getKey().toString().split("\\.")[0],
                            ((String) entry.getValue()).trim());
                }

            } else {
                String key = (String) entry.getKey(); // e.g. 10090.xref
                String value = ((String) entry.getValue()).trim(); // e.g. ZFIN
                String[] attributes = key.split("\\.");
                if (attributes.length == 0) {
                    throw new RuntimeException("Problem loading properties '"
                            + PROP_FILE + "' on line " + key);
                }

                String taxonId = attributes[0];
                if ("xref".equals(attributes[1])) {
                    config_xref.put(taxonId, value);
                } else if ("prefix".equals(attributes[1])) {
                    config_prefix.put(taxonId, value);
                }
            }
        }
    }

    /**
     * Merge all sets in a collection to a single set
     * @param colOfCols a collection of HashSet
     * @return a set of strings
     */
    private Set<String> flattenCollections(Collection<Set<String>> colOfCols) {
        Set<String> all = new HashSet<String>();
        for (Set<String> col : colOfCols) {
            all.addAll(col);
        }
        return all;
    }

    /**
     * Get strain taxons
     */
    private Map<String, String> getStrain(Set<String> taxonIds) {
        Map<String, String> newTaxons = new HashMap<String, String>();
        for (String taxon : taxonIds) {
            if (config_strains.containsKey(taxon)) {
                newTaxons.put(config_strains.get(taxon), taxon);
            } else {
                newTaxons.put(taxon, taxon);
            }
        }
        return newTaxons;
    }

    @Override
    // Not implemented. TaxonId is needed as argument
    protected void createIdResolver() {
    }
}
