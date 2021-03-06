package edu.unm.health.biocomp.hscaf;

import java.io.*;
import java.util.*;
import java.math.*;
import java.lang.InterruptedException;

import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentMutableConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.SecondaryIndex;
import com.sleepycat.persist.StoreConfig;
import com.sleepycat.je.DatabaseException;

import chemaxon.formats.MolFormatException;

/**	Initiate and provide local storage &amp; retrieval via BerkeleyDB.
	For large jobs this avoids memory limits and improves performance by
	storing solved scaffolds for rapid lookup and avoiding re-calculation.
	<br>
	Note that ScaffoldSet is an alternative for in-memory storage,
	for if BerkeleyDB is unavailable or for small datasets.
	<br>
	For largest jobs use ScaffoldDB (PostgreSQL).
	@author Jeremy J Yang
 */
public class ScaffoldStore
{
  private EnvironmentConfig envConf = new EnvironmentConfig();
  private Environment env;
  private StoreConfig storeConf = new StoreConfig();
  private EntityStore scafEntityStore;
  private Boolean stereo;
  private Boolean keep_nitro_attachments;
  
  protected PrimaryIndex<Long, ScaffoldStoreEntity> scaffoldById;
  protected SecondaryIndex<String, Long, ScaffoldStoreEntity> scaffoldByCanSmi;

  /////////////////////////////////////////////////////////////////////////////
  /**	Constructor creates or opens existing database in directory specified.
	Tuning and configuration would be addressed here.
  */
  public ScaffoldStore(File location,Boolean stereo,Boolean keep_nitro_attachments)
	throws DatabaseException
  {
    this.envConf.setAllowCreate(true);
    this.envConf.setTransactional(true);
    this.env = new Environment(location,envConf);
    //EnvironmentMutableConfig emconfig = env.getMutableConfig();
    //emconfig.setCacheSize(emconfig.getCacheSize()*2);
    //System.err.println("DEBUG: stats = "+getStatsString());
    this.storeConf.setAllowCreate(true);
    this.storeConf.setTransactional(true);
    this.scafEntityStore = new EntityStore(env,"scaffolds",storeConf);
    this.scaffoldById = this.scafEntityStore.getPrimaryIndex(Long.class, ScaffoldStoreEntity.class);
    this.scaffoldByCanSmi = this.scafEntityStore.getSecondaryIndex(scaffoldById, String.class, "canSmi");
    this.stereo=stereo;
    this.keep_nitro_attachments=keep_nitro_attachments;
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Returns number of scaffolds in ScaffoldStore.
  */
  public long count()
	throws DatabaseException
  {
    return this.scafEntityStore.getPrimaryIndex(Long.class,ScaffoldStoreEntity.class).count();
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Returns information about contents of ScaffoldStore and underlying
	BerkeleyDB database and environment.
  */
  public String info(int verbose)
        throws DatabaseException
  {
    String txt="";
    if (verbose==0) return "";

    txt+="config:\n";
    txt+=("\tstereo: "+this.isStereo()+"\n");
    txt+=("\tkeep_nitro_attachments: "+this.isKeep_nitro_attachments()+"\n");
    txt+=("count() = "+this.count()+"\n");
    Long id_max=0L;
    Transaction txn = this.env.beginTransaction(null, null);
    EntityCursor<ScaffoldStoreEntity> curr = this.scaffoldById.entities(txn, CursorConfig.DEFAULT);
    for (ScaffoldStoreEntity scaf=curr.first(); scaf!=null; scaf=curr.next())
      id_max=Math.max(id_max,scaf.getId());
    curr.close();
    txn.commitNoSync();
    txt+=("id_max = "+id_max+"\n");
    if (verbose>1)
    {
      txt+="databases:\n";
      for (String dbName : this.env.getDatabaseNames())
        txt+=("\t"+dbName+"\n");
    }
    if (verbose>3)
      txt+=("envstats = "+this.env.getStats(null).toString()+"\n");
    return txt;
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Close all databases and environment.
  */
  public void closeAll()
	throws DatabaseException
  {
    this.scafEntityStore.getSequence("ID").close();	//Maybe help with sequence problem?
    this.scafEntityStore.close();
    this.env.cleanLog();
    this.env.close();
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Delete all data from scaffold databases.
  */
  public void removeAll()
	throws DatabaseException
  {
    //Transaction txn = this.env.beginTransaction(null, null);
    //EntityCursor<ScaffoldStoreEntity> curr = this.scaffoldById.entities(txn, CursorConfig.DEFAULT);
    //for(ScaffoldStoreEntity scaf = curr.first(); scaf != null; scaf = curr.next()) {
    //  curr.delete();
    //}
    //curr.close();
    //txn.commit();

    this.scafEntityStore.close();
    for (String dbName : this.env.getDatabaseNames())
    { 
      if (dbName.matches("^persist#scaffolds#.*$"))
      {
        boolean ok=false;
        int maxTries=3;
        int sleep_ms=1000;
        for (int i=0;i<maxTries && !ok;++i)
        {
          if (i>0) { // Sleep before retrying.
            try { Thread.sleep(sleep_ms); }
            catch (InterruptedException e) { }
          }
          Transaction txn=null;
          try {
            txn=this.env.beginTransaction(null, null);
            this.env.verify(null, System.err);
            long count=this.env.truncateDatabase(txn,dbName,true);
            //long count=this.env.truncateDatabase(null,dbName,true); //non-transactional way
            txn.commit();
            ok=true;
          }
          catch (DatabaseException e) {
            System.err.println(e.getMessage());
            continue;
          }
          finally {
            if (!ok && txn!=null) { txn.abort(); }
          }
        }
      }
    }
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Destroy databases (empty files may remain).
  */
  public void destroyDB()
	throws DatabaseException
  {
    this.scafEntityStore.close();
    for (String dbName : this.env.getDatabaseNames())
    { 
      if (dbName.matches("^persist#scaffolds#.*$"))
      {
        boolean ok=false;
        int maxTries=3;
        int sleep_ms=1000;
        for (int i=0;i<maxTries && !ok;++i)
        {
          if (i>0) { // Sleep before retrying.
            try { Thread.sleep(sleep_ms); }
            catch (InterruptedException e) { }
          }
          Transaction txn=null;
          try {
            txn=this.env.beginTransaction(null, null);
            this.env.removeDatabase(txn,dbName);
            txn.commit();
            ok=true;
          }
          catch (DatabaseException e) {
            System.err.println(e.getMessage());
            continue;
          } finally {
            if (!ok && txn!=null) { txn.abort(); }
          }
        }
      }
    }
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Merges a fully populated ScaffoldTree defined by the Scaffold
	argument with the ScaffoldStore.  

	New scaffolds are assigned new IDs.  Existing scaffolds are
	recognized as such.  Initially called with new root scaffold,
	then recursively.  For each call, process all immediate child
	scaffolds, then assign ChildIds and ParentId to ScaffoldStoreEntity.

	Note that a new unknown parent may have known and/or new child scaffolds.	
	Hence this recursive method must handle the known-scaffold case.
  */
  public int mergeScaffoldTree(Scaffold scaf)
    throws DatabaseException
  {
    int n_new=0;
    String cansmi=scaf.getCansmi();
    //System.err.println("DEBUG: (mergeScaffoldTree) scafsmi="+cansmi);
    long id=0L;
    // First either find existing or create new ScaffoldStoreEntity.
    if (this.scaffoldByCanSmi.contains(cansmi))
    {
      id=this.scaffoldByCanSmi.get(cansmi).getId();
      //System.err.println("DEBUG: (mergeScaffoldTree) scaf found; id="+id);
    }
    else
    {
      id=this.count()+1;	//new ID (manual)
      this.scaffoldById.put(new ScaffoldStoreEntity(id,cansmi));
      ++n_new;
    }
    scaf.setID(id);
    ScaffoldStoreEntity scent=this.scaffoldById.get(id);

    // For each child, find existing or create new ScaffoldStoreEntity, then recurse.
    for (Scaffold cscaf : scaf.getChildScaffolds())
    {
      cansmi=cscaf.getCansmi();
      long cid=0L;
      if (this.scaffoldByCanSmi.contains(cansmi))
      {
        cid=this.scaffoldByCanSmi.get(cansmi).getId();
      }
      else
      {
        cid=this.count()+1;	//new ID (manual)
        this.scaffoldById.put(new ScaffoldStoreEntity(cid,cansmi));
        ++n_new;
      }
      ScaffoldStoreEntity cscent=this.scaffoldById.get(cid);
      cscaf.setID(cid);
      scent.addChildId(cid);
      this.scaffoldById.put(scent); //update
      //System.err.println("DEBUG: (mergeScaffoldTree) ID="+id+" scent.getChildIds() "+scent.getChildIds());
      //System.err.println("DEBUG: (mergeScaffoldTree) CID="+cid+" cscent.setParentId("+id+") ...");
      cscent.setParentId(id);
      this.scaffoldById.put(cscent); //update
      //System.err.println("DEBUG: (mergeScaffoldTree) CID="+cid+" ID="+id+" cscent.getParentId() "+cscent.getParentId());
      //DEBUG_dump(); //DEBUG

      n_new+=mergeScaffoldTree(cscaf);
    }
    return n_new;
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Populates an incomplete ScaffoldTree from known root Scaffold.
	Note that since root scaffold is known, all child scaffolds must also
	be known.

	For known scaffold, obtain ID from store, and find child scaffolds
	in store to populate ScaffoldTree.
  */
  public int populateScaffoldTree(Scaffold scaf)
    throws DatabaseException
  {
    int n_scaf=0;
    //System.err.println("DEBUG: (populateScaffoldTree); scaf.getID() = "+scaf.getID());
    if (!this.scaffoldById.contains(scaf.getID()))
    {
      //System.err.println("DEBUG: (populateScaffoldTree) aaack! ID not found...");
      return 0; //Should not happen.
    }
    ScaffoldStoreEntity scent = this.scaffoldById.get(scaf.getID());
    HashSet<Long> chids = scent.getChildIds();
    //System.err.println("DEBUG: (populateScaffoldTree) chids.size() = "+chids.size());
    for (Iterator<Long> itr=chids.iterator(); itr.hasNext(); )
    {
      long chid=itr.next();
      //if (chid==0) System.err.println("DEBUG: (populateScaffoldTree) ERROR; chid = "+chid);
      ScaffoldStoreEntity cscent = this.scaffoldById.get(chid);
      String cansmi = cscent.getCanSmi();
      //System.err.println("DEBUG: (populateScaffoldTree) chid = "+chid+" ; smi = "+cansmi);
      Scaffold cscaf = null;
      try { cscaf = new Scaffold(cansmi,this.isStereo(),this.isKeep_nitro_attachments()); }
      catch (MolFormatException e) { System.err.println("DEBUG: aaack! (populateScaffoldTree)"); } //Should not happen.
      cscaf.setID(chid);
      scaf.addChild(cscaf);
      ++n_scaf;
      n_scaf+=populateScaffoldTree(cscaf);      //recurse
    }
    //System.err.println("DEBUG: (populateScaffoldTree) leaving... ; n_scaf = "+n_scaf);
    return n_scaf;
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Returns BerkeleyDB environment stats.
  */
  public String getStatsString()
	throws DatabaseException
  {
    return this.env.getStats(null).toString();
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Generates string representing the hierarchical scaffold sub tree
        rooted by this scaffold. Same format as Scaffold.subTreeAsString().
	e.g. "1(2,3)" or "1(2(3,4,5),6(4,7))"
  */
  public String getScaffoldString(ScaffoldStoreEntity scent)
	throws DatabaseException
  {
    if (scent==null) return "";
    String str=""+scent.getId();
    if (scent.getChildIds().size()>0)
    {
      //System.err.println("DEBUG: scent.getChildIds().size()="+scent.getChildIds().size());
      str+="(";
      long i=0L;
      HashSet<Long> chids = scent.getChildIds();
      for (Iterator<Long> itr=chids.iterator(); itr.hasNext(); )
      {
        long id=itr.next();
        ScaffoldStoreEntity cscent=this.scaffoldById.get(id);
        str+=(((i++>0)?",":"")+this.getScaffoldString(cscent)); //recurse
      }
      str+=")";
    }
    return str;
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Is stereo flag on for scaffolds in this ScaffoldStore.
  */
  public Boolean isStereo()
  {
    return this.stereo;
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Is keep_nitro_attachments flag on for scaffolds in this ScaffoldStore.
  */
  public Boolean isKeep_nitro_attachments()
  {
    return this.keep_nitro_attachments;
  }
  /////////////////////////////////////////////////////////////////////////////
  /**	Dump all scaffolds in this ScaffoldStore to file; format is 
	legal smiles file and normal hier_scaffolds output:
	(SMILES ID SCAFTREE).  Returns number of scaffolds
	written.
  */
  public long dumpToFile(File fout,int verbose)
        throws IOException,DatabaseException
  {
    long nscaf=0;
    if (verbose>0)
    {
      System.err.println("Dumping scaffold store to file.");
      System.err.println(this.info(verbose));
    }
    PrintWriter fout_writer=new PrintWriter(new BufferedWriter(new FileWriter(fout,false))); //overwrite

    for (long scaf_id=1L;true;++scaf_id)
    {
      ScaffoldStoreEntity scent=null;
      try {
        scent=this.scaffoldById.get(scaf_id);
      }
      catch (Exception e) { break; } //Must be EOF? 
      if (scent==null) break;
      fout_writer.printf("%s %d %s\n",scent.getCanSmi(),scaf_id,getScaffoldString(scent));
      ++nscaf;
    }
    fout_writer.close();
    return nscaf;
  }
}
