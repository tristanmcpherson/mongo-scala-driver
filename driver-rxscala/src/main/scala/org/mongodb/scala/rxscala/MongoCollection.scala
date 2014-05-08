/**
 * Copyright (c) 2014 MongoDB, Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 * For questions and comments about this product, please see the project page at:
 *
 * https://github.com/mongodb/mongo-scala-driver
 *
 */
package org.mongodb.scala.rxscala

import scala.Some
import scala.collection.JavaConverters._

import org.mongodb.{CollectibleCodec, ConvertibleToDocument, Document, MongoNamespace, QueryOptions, ReadPreference, WriteConcern, WriteResult}
import org.mongodb.operation._

import org.mongodb.scala.core.MongoCollectionOptions
import org.mongodb.scala.rxscala.admin.MongoCollectionAdmin
import org.mongodb.scala.rxscala.utils.HandleCommandResponse

import rx.lang.scala.Observable

// scalastyle:off number.of.methods

/**
 * A MongoDB Collection
 *
 * @param name The name of the collection
 * @param database The database that this collection belongs to
 * @param codec The collectable codec to use for operations
 * @param options The options to use with this collection
 * @tparam T
 */
case class MongoCollection[T](name: String,
                              database: MongoDatabase,
                              codec: CollectibleCodec[T],
                              options: MongoCollectionOptions) {

  /**
   * The MongoCollectionAdmin which provides admin methods for a collection
   */
  val admin = MongoCollectionAdmin[T](this)
  /**
   * The MongoClient
   */
  val client: MongoClient = database.client

  /**
   * The namespace for any operations
   */
  private[scala] val namespace: MongoNamespace = new MongoNamespace(database.name, name)

  /**
   * Insert a document into the database
   * @param document to be inserted
   */
  def insert(document: T): Observable[WriteResult] = MongoCollectionView[T]().insert(document)

  /**
   * Insert a document into the database
   * @param documents the documents to be inserted
   */
  def insert(documents: Iterable[T]): Observable[WriteResult] = MongoCollectionView[T]().insert(documents)

  /**
   * Count the number of documents
   */
  def count(): Observable[Long] = MongoCollectionView[T]().count()

  /**
   * Get a cursor which is a [[https://github.com/Netflix/RxJava/wiki/Subject Subject]]
   */
  def cursor(): Observable[T] = MongoCollectionView[T]().cursor()

  /**
   * Return a list of results (memory hungry)
   */
  def toList(): Observable[List[T]] = MongoCollectionView[T]().toList()

  //  /**
  //   * Run a transforming operation foreach resulting document.
  //   *
  //   * @param f the transforming function to apply to each document
  //   * @tparam A The resultant type
  //   * @return Observable[A]
  //   */
  //  def map[A](f: T => A): Observable[A] = MongoCollectionView[T]().map(f)

  def find(filter: ConvertibleToDocument): MongoCollectionView[T] = MongoCollectionView[T]().find(filter.toDocument)

  def find(filter: Document): MongoCollectionView[T] = MongoCollectionView[T]().find(filter)


  /**
   * Companion object for the chainable MongoCollectionView
   */
  protected object MongoCollectionView {

    /**
     * Creates the inital collection view - a find all.
     * @tparam D The document type
     * @return A composable MongoCollectionView
     */
    def apply[D](): MongoCollectionView[D] = {
      val findOp: Find = new Find()
      MongoCollectionView[D](findOp, options.writeConcern, limitSet = false, doUpsert = false, options.readPreference)
    }
  }


  protected case class MongoCollectionView[D](findOp: Find, writeConcern: WriteConcern,
                                              limitSet: Boolean, doUpsert: Boolean,
                                              readPreference: ReadPreference) extends HandleCommandResponse {
    /**
     * The document codec to use
     */
    val documentCodec = options.documentCodec

    /**
     * The command codec to use
     */
    def getCodec: CollectibleCodec[D] = codec.asInstanceOf[CollectibleCodec[D]]

    /**
     * Insert a document into the database
     * @param document to be inserted
     */
    def insert(document: D): Observable[WriteResult] = insert(List(document))

    /**
     * Insert a document into the database
     * @param documents the documents to be inserted
     */
    def insert(documents: Iterable[D]): Observable[WriteResult] = {
      val insertRequestList = documents.map(new InsertRequest[D](_)).toList.asJava
      val operation = new InsertOperation[D](namespace, true, writeConcern, insertRequestList, getCodec)
      client.executeAsync(operation)
    }

    /**
     * Count the number of documents
     */
    def count(): Observable[Long] = {
      val operation = new CountOperation(namespace, findOp, database.documentCodec)
      client.executeAsync(operation, options.readPreference).asInstanceOf[Observable[Long]]
    }

    /**
     * Filter the collection
     * @param filter the query to perform
     */
    def find(filter: ConvertibleToDocument): MongoCollectionView[D] = find(filter.toDocument)

    /**
     * Filter the collection
     * @param filter the query to perform
     */
    def find(filter: Document): MongoCollectionView[D] = {
      copy(findOp = findOp.filter(filter))
    }

    /**
     * Return a single list of results (memory hungry)
     */
    def toList(): Observable[List[D]] = cursor().foldLeft(List[D]()){(docs, doc) => docs :+ doc  }

    /**
     * Sort the results
     * @param sortCriteria the sort criteria
     */
    def sort(sortCriteria: ConvertibleToDocument): MongoCollectionView[D] = sort(sortCriteria.toDocument)

    /**
     * Sort the results
     * @param sortCriteria the sort criteria
     */
    def sort(sortCriteria: Document): MongoCollectionView[D] = {
      copy(findOp = findOp.order(sortCriteria))
    }

    /**
     * Fields to include / exclude with the output
     *
     * @param selector the fields to include / exclude
     */
    def fields(selector: ConvertibleToDocument): MongoCollectionView[D] = fields(selector.toDocument)

    /**
     * Fields to include / exclude with the output
     *
     * @param selector the fields to include / exclude
     */
    def fields(selector: Document): MongoCollectionView[D] = copy(findOp = findOp.select(selector))

    /**
     * Create a new document when no document matches the query criteria when doing an insert.
     */
    def upsert: MongoCollectionView[D] = copy(doUpsert = true)

    /**
     * Set custom query options for the query
     * @param queryOptions the options to use for the cursor
     * @see [[http://docs.mongodb.org/manual/reference/method/cursor.addOption/#cursor-flags Cursor Options]]
     */
    def withQueryOptions(queryOptions: QueryOptions): MongoCollectionView[D] = {
      val view: MongoCollectionView[D] = copy()
      view.findOp.options(queryOptions)
      view
    }

    /**
     * Skip a number of documents
     * @param skip the number to skip
     */
    def skip(skip: Int): MongoCollectionView[D] = copy(findOp = findOp.skip(skip))

    /**
     * Limit the resulting results
     * @param limit the number to limit to
     */
    def limit(limit: Int): MongoCollectionView[D] = copy(findOp = findOp.limit(limit), limitSet = true)

    /**
     * Use a custom read preference to determine how MongoDB clients route read operations to members of a replica set
     * @param readPreference the read preference for the query
     * @see [[http://docs.mongodb.org/manual/core/read-preference read preference]]
     */
    def withReadPreference(readPreference: ReadPreference): MongoCollectionView[D] =
      copy(readPreference = readPreference)

    /**
     * Execute the operation and return the result.
     */
    def cursor(): Observable[D] = {
      val operation = new QueryOperation[D](namespace, findOp, documentCodec, getCodec)
      client.executeAsync(operation, ReadPreference.primary)
    }

    def one(): Observable[Option[D]] = {
      limit(1).cursor().take(1).foldLeft[Option[D]](None)((v: Option[D], doc: D) => Some(doc))
    }

    def withWriteConcern(writeConcernForThisOperation: WriteConcern): MongoCollectionView[T] =
      copy(writeConcern = writeConcernForThisOperation)

    def save(document: D): Observable[WriteResult] = {
      Option(getCodec.getId(document)) match {
        case None => insert(document)
        case Some(id) => upsert.find(new Document("_id", id)).replace(document)
      }
    }

    def remove(): Observable[WriteResult] = {
      val removeRequest: List[RemoveRequest] = List(new RemoveRequest(findOp.getFilter).multi(getMultiFromLimit))
      val operation = new RemoveOperation(namespace, true, writeConcern, removeRequest.asJava, documentCodec)
      client.executeAsync(operation)
    }

    def removeOne(): Observable[WriteResult] = {
      val removeRequest: List[RemoveRequest] = List(new RemoveRequest(findOp.getFilter).multi(false))
      val operation = new RemoveOperation(namespace, true, writeConcern, removeRequest.asJava, documentCodec)
      client.executeAsync(operation)
    }

    def update(updateOperations: ConvertibleToDocument): Observable[WriteResult] = update(updateOperations.toDocument)

    def update(updateOperations: Document): Observable[WriteResult] = {
      val updateRequest: List[UpdateRequest] = List(new UpdateRequest(findOp.getFilter, updateOperations).upsert(doUpsert).multi(getMultiFromLimit))
      val operation = new UpdateOperation(namespace, true, writeConcern, updateRequest.asJava, documentCodec)
      client.executeAsync(operation)
    }

    def updateOne(updateOperations: ConvertibleToDocument): Observable[WriteResult] = updateOne(updateOperations.toDocument)

    def updateOne(updateOperations: Document): Observable[WriteResult] = {
      val updateRequest: List[UpdateRequest] = List(new UpdateRequest(findOp.getFilter, updateOperations).upsert(doUpsert).multi(false))
      val operation = new UpdateOperation(namespace, true, writeConcern, updateRequest.asJava, documentCodec)
      client.executeAsync(operation)
    }

    def replace(replacement: D): Observable[WriteResult] = {
      val replaceRequest: List[ReplaceRequest[D]] = List(new ReplaceRequest[D](findOp.getFilter, replacement).upsert(doUpsert))
      val operation = new ReplaceOperation(namespace, true, writeConcern, replaceRequest.asJava, documentCodec, getCodec)
      client.executeAsync(operation)
    }

    def updateOneAndGet(updateOperations: Document): Observable[D] = updateOneAndGet(updateOperations, returnNew = true)

    def updateOneAndGet(updateOperations: ConvertibleToDocument): Observable[D] = updateOneAndGet(updateOperations.toDocument)

    def updateOneAndGet(updateOperations: Document, returnNew: Boolean): Observable[D] = {
      val findAndUpdate: FindAndUpdate = new FindAndUpdate()
        .where(findOp.getFilter)
        .updateWith(updateOperations)
        .returnNew(returnNew)
        .select(findOp.getFields)
        .sortBy(findOp.getOrder)
        .upsert(doUpsert)
      val operation = new FindAndUpdateOperation[D](namespace, findAndUpdate, getCodec)
      client.executeAsync(operation)
    }

    def getOneAndUpdate(updateOperations: Document): Observable[D] = updateOneAndGet(updateOperations, returnNew = false)

    def getOneAndUpdate(updateOperations: ConvertibleToDocument): Observable[D] = getOneAndUpdate(updateOperations.toDocument)

    def getOneAndReplace(replacement: D): Observable[D] = replaceOneAndGet(replacement, returnNew = false)

    def replaceOneAndGet(replacement: D): Observable[D] = replaceOneAndGet(replacement, returnNew = true)

    def replaceOneAndGet(replacement: D, returnNew: Boolean): Observable[D] = {
      val findAndReplace: FindAndReplace[D] = new FindAndReplace[D](replacement)
        .where(findOp.getFilter)
        .returnNew(returnNew)
        .select(findOp.getFields)
        .sortBy(findOp.getOrder)
        .upsert(doUpsert)
      val operation = new FindAndReplaceOperation[D](namespace, findAndReplace, getCodec, getCodec)
      client.executeAsync(operation)
    }

    def getOneAndRemove: Observable[D] = {
      val findAndRemove: FindAndRemove[D] = new FindAndRemove[D]().where(findOp.getFilter).select(findOp.getFields).sortBy(findOp.getOrder)
      val operation = new FindAndRemoveOperation[D](namespace, findAndRemove, getCodec)
      client.executeAsync(operation)
    }

    private def getMultiFromLimit: Boolean = {
      findOp.getLimit match {
        case 1 => false
        case 0 => true
        case _ => throw new IllegalArgumentException("Update currently only supports a limit of either none or 1")
      }
    }

  }
}

// scalastyle:on number.of.methods
