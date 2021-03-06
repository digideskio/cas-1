/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package de.triology.cas.services;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.promises.EtcdResponsePromise;
import mousio.etcd4j.responses.EtcdAuthenticationException;
import mousio.etcd4j.responses.EtcdException;
import mousio.etcd4j.responses.EtcdKeysResponse;
import org.apache.commons.lang.StringUtils;
import org.jasig.cas.authentication.principal.Service;
import org.jasig.cas.services.RegexRegisteredService;
import org.jasig.cas.services.RegisteredService;
import org.jasig.cas.services.ReloadableServicesManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds for each dogu, which needs cas, a service to registeredServices. These
 * dogus getting identified with etcd: Installed dogus have a directory
 * '/dogu/${name of dogu}/current' with their used version. Further 'cas' has to
 * be in the dependencies of the dogu. Changes of the '/dogu' directory will be
 * noticed and registeredServices updated. Every service will be accepted if the
 * ecosystem is in development stage.
 *
 * @author Michael Behlendorf
 */
public final class EtcdServicesManager implements ReloadableServicesManager {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Map to store all registeredServices.
     */
    private ConcurrentHashMap<Long, RegisteredService> registeredServices = new ConcurrentHashMap<>();

    private String fqdn;

    private final List<String> allowedAttributes;

    private final JSONParser parser = new JSONParser();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private final EtcdClient etcd;

    public EtcdServicesManager(List<String> allowedAttributes, String stage) throws IOException, EtcdException, EtcdAuthenticationException, TimeoutException, ParseException {
        this.allowedAttributes = allowedAttributes;
        // depends on stage configured in cas.properties
        if (!stage.equals("development")) {
            // check which stage is set in etcd
            etcd = new EtcdClient(URI.create(getEtcdUri()));
            try {
                EtcdKeysResponse stageResponse = etcd.get("/config/_global/stage").send().get();
                stage = stageResponse.getNode().getValue();
            } catch (EtcdException ex) {
                logger.warn("/config/_global/stage could not be read", ex);
                stage = "production";
            }
            if (stage.equals("development")) {
                initDevMode();
            } else {
                logger.debug("cas started in production stage");
                logger.debug("only installed dogus can get a ST");
                EtcdResponsePromise<EtcdKeysResponse> response1 = etcd.getDir("/dogu").recursive().send();
                EtcdKeysResponse response2 = etcd.get("/config/_global/fqdn").send().get();
                fqdn = response2.getNode().getValue();
                addServices(response1.get());
                changeLoop();
            }
        } else {
            etcd = null;
            initDevMode();
        }
    }

    private void initDevMode() {
        logger.debug("cas started in development stage");
        logger.debug("all services can get a ST");
        addDevService();
    }

    private void changeLoop() {
        logger.debug("entered changeLoop");
        try {
            EtcdResponsePromise responsePromise = etcd.getDir("/dogu").recursive().waitForChange().send();
            responsePromise.addListener(promise -> {
                logger.debug("registered change in /dogu");
                load();
                changeLoop();
            });
        } catch (IOException ex) {
            logger.error("failed to load service", ex);
        }
    }

    private String getEtcdUri() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("/etc/ces/node_master"))) {
            String nodeMaster = reader.readLine();
            if (StringUtils.isBlank(nodeMaster)) {
                throw new IOException("failed to read node_master file");
            }
            return "http://".concat(nodeMaster).concat(":4001");
        }
    }

    private long findHighestId(ConcurrentHashMap<Long, RegisteredService> map) {
        long id = 0;

        for (Entry<Long, RegisteredService> entry : map.entrySet()) {
            if (entry.getKey() > id) {
                id = entry.getKey();
            }
        }
        return id;
    }

    private JSONObject getCurrentDoguNode(EtcdKeysResponse.EtcdNode doguNode) throws ParseException {
        String version = "";
        JSONObject json = null;
        // get used dogu version
        for (EtcdKeysResponse.EtcdNode leaf : doguNode.getNodes()) {
            if (leaf.getKey().equals(doguNode.getKey() + "/current")) {
                version = leaf.getValue();
            }

        }
        // empty if dogu isnt used
        if (!version.isEmpty()) {
            for (EtcdKeysResponse.EtcdNode leaf : doguNode.getNodes()) {
                if (leaf.getKey().equals(doguNode.getKey() + "/" + version)) {

                    json = (JSONObject) parser.parse(leaf.getValue());
                }
            }
        }

        return json;

    }

    private boolean hasCasDependency(JSONObject json) {
        return json != null && json.get("Dependencies") != null && ((JSONArray) json.get("Dependencies")).contains("cas");
    }

    private RegexRegisteredService createService(EtcdKeysResponse.EtcdNode doguNode) throws ParseException {
        JSONObject json = getCurrentDoguNode(doguNode);
        // check if dogu needs cas
        if (hasCasDependency(json)) {
            RegexRegisteredService service = new RegexRegisteredService();
            String[] nameArray = StringUtils.split(json.get("Name").toString(),"/");
            service.setAllowedToProxy(true);
            service.setName(json.get("Name").toString());
            service.setServiceId("https://" + fqdn + "(:443)?/" + nameArray[nameArray.length-1] + "(/.*)?");
            service.setEvaluationOrder((int) service.getId());
            service.setAllowedAttributes(allowedAttributes);
            return service;
        }
        return null;
    }

    private void addServices(EtcdKeysResponse response) throws ParseException {
        final ConcurrentHashMap<Long, RegisteredService> localServices
                = new ConcurrentHashMap<>();
        // get all dogu nodes
        for (EtcdKeysResponse.EtcdNode doguNode : response.getNode().getNodes()) {
            RegexRegisteredService service = createService(doguNode);
            if (service != null) {
                service.setId(findHighestId(localServices) + 1);
                localServices.put(service.getId(), service);
            }
        }
        addCasService(localServices);
        try {
            lock.writeLock().lock();
            registeredServices = localServices;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    // this is necessary since cas needs a ST in clearPass workflow
    private void addCasService(ConcurrentHashMap<Long, RegisteredService> localServices) {
        RegexRegisteredService service = new RegexRegisteredService();
        service.setAllowedToProxy(true);
        service.setName("cas");
        service.setServiceId("https://" + fqdn + "/cas/.*");
        service.setEvaluationOrder((int) service.getId());
        service.setAllowedAttributes(allowedAttributes);
        service.setId(findHighestId(localServices) + 1);
        localServices.put(service.getId(), service);
    }

    private void addDevService() {
        RegexRegisteredService regexService = new RegexRegisteredService();
        regexService.setServiceId("^(https?|imaps?)://.*");
        regexService.setId(0);
        regexService.setName("10000001");
        regexService.setAllowedToProxy(true);
        regexService.setAllowedAttributes(allowedAttributes);
        registeredServices.put(regexService.getId(), regexService);
    }

    @Override
    public RegisteredService delete(final long id) {
        throw new UnsupportedOperationException("Operation delete is not supported.");
    }

    @Override
    public RegisteredService findServiceBy(final Service service) {
        try {
            lock.readLock().lock();
            final Collection<RegisteredService> c = convertToTreeSet();

            for (final RegisteredService r : c) {
                if (r.matches(service)) {
                    return r;
                }
            }

            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public RegisteredService findServiceBy(final long id) {
        try {
            lock.readLock().lock();
            final RegisteredService r = this.registeredServices.get(id);

            try {
                return r == null ? null : r.clone();
            } catch (final CloneNotSupportedException e) {
                return r;
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    protected TreeSet<RegisteredService> convertToTreeSet() {           
        return new TreeSet<>(this.registeredServices.values());
    }

    @Override
    public Collection<RegisteredService> getAllServices() {
        try {
            lock.readLock().lock();
            return Collections.unmodifiableCollection(convertToTreeSet());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean matchesExistingService(final Service service) {
        return findServiceBy(service) != null;
    }

    @Override
    public RegisteredService save(final RegisteredService registeredService) {
        throw new UnsupportedOperationException("Operation save is not supported.");
    }

    @Override
    public void reload() {
        logger.info("Cas wants to reload registered services.");
    }

    private void load() {
        try {
            addServices(etcd.getDir("/dogu").recursive().send().get());
        } catch (IOException | EtcdException | EtcdAuthenticationException | TimeoutException | ParseException ex) {
            logger.warn("failed to update servicesManager", ex);
        }
        logger.info(String.format("Loaded %s services.", this.registeredServices.size()));
    }

}
