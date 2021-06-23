package be.ugent;

import com.buildingsmart.tech.ifcowl.vo.IFCVO;
import org.apache.commons.lang3.time.StopWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.invoke.MethodHandles;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class IfcSpfParser {
    private final boolean resolveDuplicates;
    private final InputStream inputStream;
    private int idCounter = 0;
    private long lineNumMax = 0;
    private final Map<Long, IFCVO> linemap = new TreeMap<>();
    private final Map<Long, Long> listOfDuplicateLineEntries = new TreeMap<>();

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public IfcSpfParser(InputStream inputStream, boolean resolveDuplicates)
    {
        this.inputStream = inputStream;
        this.resolveDuplicates = resolveDuplicates;
    }


    public void readModel() {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
                String strLine;
                int cnt = 0;
                while ((strLine = br.readLine()) != null) {
                    cnt++;
                    if (cnt % 10000 == 0) {
                        LOG.debug("parsed: {} lines", cnt);
                    }
                    if (strLine.length() > 0) {
                        if (strLine.charAt(0) == '#') {
                            StringBuilder sb = new StringBuilder();
                            String stmp = strLine;
                            sb.append(stmp.trim());
                            while (!stmp.contains(";")) {
                                stmp = br.readLine();
                                if (stmp == null)
                                    break;
                                sb.append(stmp.trim());
                            }
                            // the whole IFC gets parsed, and everything ends up
                            // as IFCVO objects in the Map<Long, IFCVO> linemap
                            // variable
                            parseIfcLineStatement(sb.substring(1));
                        }
                    }
                }
                LOG.debug("done reading");
            } finally {
                if (lineNumMax > idCounter) {
                    idCounter = (int) lineNumMax;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        stopWatch.stop();
        LOG.info("Done reading model in {}", Duration.of(stopWatch.getTime(TimeUnit.NANOSECONDS), ChronoUnit.NANOS));
    }

    private void parseIfcLineStatement(String line) {
        IFCVO ifcvo = new IFCVO();
        if (resolveDuplicates) {
            // remembering the full line costs a lot of memory, we only do it if needed
            ifcvo.setFullLineAfterNum(line.substring(line.indexOf('=') + 1));
        }
        int state = 0;
        int clCount = 0;
        StringBuilder sb = new StringBuilder();
        List<Object> current = new LinkedList<>();
        Deque<List<Object>> listStack = new ArrayDeque<>();
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            switch (state) {
                case 0:
                    if (ch == '=') {
                        ifcvo.setLineNum(toLong(sb.toString()));
                        if(toLong(sb.toString()) > lineNumMax)
                            lineNumMax = toLong(sb.toString());
                        sb.setLength(0);
                        state++;
                        continue;
                    } else if (Character.isDigit(ch))
                        sb.append(ch);
                    break;
                case 1: // (
                    if (ch == '(') {
                        ifcvo.setName(sb.toString());
                        sb.setLength(0);
                        state++;
                        continue;
                    } else if (ch == ';') {
                        ifcvo.setName(sb.toString());
                        sb.setLength(0);
                        state = Integer.MAX_VALUE;
                    } else if (!Character.isWhitespace(ch))
                        sb.append(ch);
                    break;
                case 2: // (... line started and doing (...
                    if (ch == '\'') {
                        state++;
                    }
                    if (ch == '(') {
                        listStack.push(current);
                        LinkedList<Object> tmp = new LinkedList<>();
                        addBufferToCurrentListIfNotEmpty(sb, current);
                        sb.setLength(0);
                        current = tmp;
                        clCount++;
                    } else if (ch == ')') {
                        if (clCount == 0) {
                            addBufferToCurrentListIfNotEmpty(sb, current);
                            sb.setLength(0);
                            state = Integer.MAX_VALUE; // line is done
                            continue;
                        } else {
                            addBufferToCurrentListIfNotEmpty(sb, current);
                            sb.setLength(0);
                            clCount--;
                            List<Object> sub = toArrayList(current);
                            current = listStack.pop();
                            current.add(sub);
                        }
                    } else if (ch == ',') {
                        addBufferToCurrentListIfNotEmpty(sb, current);
                        current.add(ch);

                        sb.setLength(0);
                    } else {
                        sb.append(ch);
                    }
                    break;
                case 3: // (...
                    if (ch == '\'') {
                        state--;
                    } else {
                        sb.append(ch);
                    }
                    break;
                default:
                    // Do nothing
            }
        }

        ifcvo.setList(toArrayList(current));
        linemap.put(ifcvo.getLineNum(), ifcvo);
        idCounter++;
    }

    private void addBufferToCurrentListIfNotEmpty(StringBuilder sb, List<Object> current) {
        String value = sb.toString().trim();
        if (value.length() > 0) {
            if (value.charAt(0) == '#'){
                Long id = Long.valueOf(value.substring(1));
                IFCVO reference = linemap.get(id);
                current.add(Objects.requireNonNullElse(reference, id));
            } else {
                current.add(value);
            }
        }
    }

    private <T> List<T> toArrayList(List<T> aList) {
        List<T> result = new ArrayList<>(aList.size());
        result.addAll(aList);
        return result;
    }

    public void resolveDuplicates() {
        Map<String, IFCVO> listOfUniqueResources = new TreeMap<>();
        List<Long> entriesToRemove = new ArrayList<>();
        for (Map.Entry<Long, IFCVO> entry : linemap.entrySet()) {
            IFCVO vo = entry.getValue();
            String t = vo.getFullLineAfterNum();
            if (!listOfUniqueResources.containsKey(t))
                listOfUniqueResources.put(t, vo);
            else {
                // found duplicate
                entriesToRemove.add(entry.getKey());
                listOfDuplicateLineEntries.put(vo.getLineNum(), listOfUniqueResources.get(t).getLineNum());
            }
        }
        LOG.info("MESSAGE: found and removed " + listOfDuplicateLineEntries.size() + " duplicates!");
        for (Long x : entriesToRemove) {
            linemap.remove(x);
        }
    }

    public boolean mapEntries() {
        int cnt = 0;
        int preRef=0;
        int postRef=0;
        for (Map.Entry<Long, IFCVO> entry : linemap.entrySet()) {
            cnt++;
            if (cnt % 10000 == 0) {
                LOG.debug("Mapped {} entries...", cnt);
            }
            IFCVO vo = entry.getValue();
            // mapping properties to IFCVOs
            List<Object> objectList = vo.getObjectList();
            int size = objectList.size();
            for (int i = 0; i < size; i++) {
                Object o = objectList.get(i);
                if (o instanceof Character) {
                    if ((Character) o != ',') {
                        LOG.error("*ERROR 15*: We found a character that is not a comma. That should not be possible");
                    }
                } else if (o instanceof IFCVO){
                    // found reference during line parsing
                    preRef++;
                } else if (o instanceof Long) {
                    Long id = (Long) o;
                    postRef++;
                    boolean success = resolveReference(objectList, i, id, vo, "*ERROR 6*");
                    if (!success) {
                        return false;
                    }
                } else if (List.class.isAssignableFrom(o.getClass())) {
                    @SuppressWarnings("unchecked")
                    List<Object> tmpList = (List<Object>) o;

                    for (int j = 0; j < tmpList.size(); j++) {
                        Object o1 = tmpList.get(j);
                        if (o1 instanceof Character) {
                            if ((Character) o1 != ',') {
                                LOG.error("*ERROR 16*: We found a character that is not a comma. "
                                                + "That should not be possible!");
                            }
                        } else if (o1 instanceof IFCVO){
                            preRef++;
                            // do nothing - we had already found the reference during line parsing
                        } else if (o1 instanceof Long) {
                            postRef++;
                            Long idSub = (Long) o1;
                            boolean success = resolveReference(tmpList, j, idSub, vo, "*ERROR 7*");
                            if (!success) {
                                return false;
                            }
                        } else if (List.class.isAssignableFrom(o1.getClass())) {
                            @SuppressWarnings("unchecked")
                            List<Object> tmp2List = (List<Object>) o1;
                            for (int j2 = 0; j2 < tmp2List.size(); j2++) {
                                Object o2 = tmp2List.get(j2);
                                if (o2 instanceof Long) {
                                    Long idSubSub = (Long) o2;
                                    postRef++;
                                    boolean success = resolveReference(tmp2List, j2, idSubSub, vo, "*ERROR 8*");
                                    if (!success) {
                                        return false;
                                    }
                                } else if (o2 instanceof IFCVO){
                                    preRef++;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("mapped {} entries", cnt);
            LOG.debug("references mapped in second pass: {}", postRef);
            LOG.debug("references mapped during line parsing: {}", preRef);
        }
        return true;
    }

    private boolean resolveReference(List<Object> objectList, int i, Long id, IFCVO vo, String errorCode) {
        Object or = null;
        Long idDup = listOfDuplicateLineEntries.get(id);
        if (idDup != null) {
            or = linemap.get(idDup);
        }
        if (or == null) {
            or = linemap.get(id);
        }
        if (or == null) {
            LOG.error("{}: Reference to non-existing line number in line: #{}={}", errorCode, vo.getLineNum(), vo.getFullLineAfterNum());
            return false;
        }
        objectList.set(i, or);
        return true;
    }

    private Long toLong(String txt) {
        try {
            return Long.valueOf(txt);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    public int getIdCounter() {
        return idCounter;
    }

    public Map<Long, IFCVO> getLinemap() {
        return linemap;
    }
}
