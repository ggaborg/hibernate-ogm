/*
 * Hibernate OGM, Domain model persistence for NoSQL datastores
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.ogm.datastore.neo4j.embedded.dialect.impl;

import static org.hibernate.ogm.datastore.neo4j.dialect.impl.NodeLabel.EMBEDDED;
import static org.hibernate.ogm.datastore.neo4j.query.parsing.cypherdsl.impl.CypherDSL.escapeIdentifier;
import static org.hibernate.ogm.util.impl.EmbeddedHelper.isPartOfEmbedded;
import static org.hibernate.ogm.util.impl.EmbeddedHelper.split;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.hibernate.ogm.datastore.neo4j.dialect.impl.BaseNeo4jAssociationQueries;
import org.hibernate.ogm.model.key.spi.AssociationKey;
import org.hibernate.ogm.model.key.spi.AssociationKeyMetadata;
import org.hibernate.ogm.model.key.spi.EntityKey;
import org.hibernate.ogm.model.key.spi.EntityKeyMetadata;
import org.hibernate.ogm.model.key.spi.RowKey;
import org.hibernate.ogm.util.impl.ArrayHelper;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Result;

/**
 * Container for the queries related to one association family in Neo4j. Unfortunately, we cannot use the same queries
 * for all associations, as Neo4j does not allow to parameterize on node labels which would be required, as the
 * association table is stored as a label.
 *
 * @author Davide D'Alto
 */
public class EmbeddedNeo4jAssociationQueries extends BaseNeo4jAssociationQueries {

	public EmbeddedNeo4jAssociationQueries(EntityKeyMetadata ownerEntityKeyMetadata, AssociationKeyMetadata associationKeyMetadata) {
		super( ownerEntityKeyMetadata, associationKeyMetadata );
	}

	/**
	 * Removes the relationship(s) representing the given association. If the association refers to an embedded entity
	 * (collection), the referenced entities are removed as well.
	 *
	 * @param executionEngine the {@link GraphDatabaseService} used to run the query
	 * @param associationKey represents the association
	 */
	@Override
	public void removeAssociation(GraphDatabaseService executionEngine, AssociationKey associationKey) {
		executionEngine.execute( removeAssociationQuery, params( associationKey.getEntityKey().getColumnValues() ) );
	}

	/**
	 * Returns the relationship corresponding to the {@link AssociationKey} and {@link RowKey}.
	 *
	 * @param executionEngine the {@link GraphDatabaseService} used to run the query
	 * @param associationKey represents the association
	 * @param rowKey represents a row in an association
	 * @return the corresponding relationship
	 */
	@Override
	public Relationship findRelationship(GraphDatabaseService executionEngine, AssociationKey associationKey, RowKey rowKey) {
		Object[] queryValues = relationshipValues( associationKey, rowKey );
		Result result = executionEngine.execute( findRelationshipQuery, params( queryValues ) );
		return singleResult( result );
	}

	@Override
	protected Object[] relationshipValues(AssociationKey associationKey, RowKey rowKey) {
		Object[] relationshipValues;
		if ( associationKey.getMetadata().getRowKeyIndexColumnNames().length > 0 ) {
			int length = associationKey.getMetadata().getRowKeyIndexColumnNames().length;
			relationshipValues = new Object[length];
			String[] indexColumnNames = associationKey.getMetadata().getRowKeyIndexColumnNames();
			for ( int i = 0; i < indexColumnNames.length; i++ ) {
				for ( int j = 0; j < rowKey.getColumnNames().length; j++ ) {
					if ( indexColumnNames[i].equals( rowKey.getColumnNames()[j] ) ) {
						relationshipValues[i] = rowKey.getColumnValues()[j];
					}
				}
			}
		}
		else {
			relationshipValues = getEntityKey( associationKey, rowKey ).getColumnValues();
		}
		Object[] queryValues = ArrayHelper.concat( associationKey.getEntityKey().getColumnValues(), relationshipValues );
		return queryValues;
	}

	/**
	 * Remove an association row
	 *
	 * @param executionEngine the {@link GraphDatabaseService} used to run the query
	 * @param associationKey represents the association
	 * @param rowKey represents a row in an association
	 */
	@Override
	public void removeAssociationRow(GraphDatabaseService executionEngine, AssociationKey associationKey, RowKey rowKey) {
		Object[] queryValues = relationshipValues( associationKey, rowKey );
		executionEngine.execute( removeAssociationRowQuery, params( queryValues ) );
	}

	/**
	 * Returns the entity key on the other side of association row represented by the given row key.
	 * <p>
	 * <b>Note:</b> May only be invoked if the row key actually contains all the columns making up that entity key.
	 * Specifically, it may <b>not</b> be invoked if the association has index columns (maps, ordered collections), as
	 * the entity key columns will not be part of the row key in this case.
	 */
	private EntityKey getEntityKey(AssociationKey associationKey, RowKey rowKey) {
		String[] associationKeyColumns = associationKey.getMetadata().getAssociatedEntityKeyMetadata().getAssociationKeyColumns();
		Object[] columnValues = new Object[associationKeyColumns.length];
		int i = 0;

		for ( String associationKeyColumn : associationKeyColumns ) {
			columnValues[i] = rowKey.getColumnValue( associationKeyColumn );
			i++;
		}

		EntityKeyMetadata entityKeyMetadata = associationKey.getMetadata().getAssociatedEntityKeyMetadata().getEntityKeyMetadata();
		return new EntityKey( entityKeyMetadata, columnValues );
	}

	/**
	 * Give an embedded association, creates all the nodes and relationships required to represent it.
	 * It assumes that the entity node containing the association already exists in the db.
	 *
	 * @param executionEngine the {@link GraphDatabaseService} to run the query
	 * @param associationKey the {@link AssociationKey} identifying the association
	 * @param embeddedKey the {@link EntityKey} identifying the embedded component
	 * @return the created {@link Relationship} that represents the association
	 */
	public Relationship createRelationshipForEmbeddedAssociation(GraphDatabaseService executionEngine, AssociationKey associationKey, EntityKey embeddedKey) {
		String query = initCreateEmbeddedAssociationQuery( associationKey, embeddedKey );
		Object[] queryValues = createRelationshipForEmbeddedQueryValues( associationKey, embeddedKey );
		return executeQuery( executionEngine, query, queryValues );
	}

	@Override
	protected String initCreateEmbeddedAssociationQuery(AssociationKey associationKey, EntityKey embeddedKey) {
		String collectionRole = associationKey.getMetadata().getCollectionRole();
		String[] embeddedColumnNames = embeddedKey.getColumnNames();
		Object[] embeddedColumnValues = embeddedKey.getColumnValues();
		String[] columnNames = associationKey.getEntityKey().getMetadata().getColumnNames();
		StringBuilder queryBuilder = new StringBuilder();
		queryBuilder.append( matchOwnerEntityNode );
		if ( isCollectionOfPrimitives( collectionRole, embeddedColumnNames ) ) {
			createRelationshipForCollectionOfPrimitivesOrMap( associationKey, collectionRole, columnNames, queryBuilder );
		}
		else {
			createRelationshipforCollectionOfComponents( associationKey, collectionRole, embeddedColumnNames, embeddedColumnValues, queryBuilder );
		}
		return queryBuilder.toString();
	}

	protected Object[] createRelationshipForEmbeddedQueryValues(AssociationKey associationKey, EntityKey embeddedKey) {
		String collectionRole = associationKey.getMetadata().getCollectionRole();
		Object[] columnValues = associationKey.getEntityKey().getColumnValues();
		if ( isCollectionOfPrimitives( collectionRole, embeddedKey.getColumnNames() ) ) {
			return ArrayHelper.concat( columnValues, embeddedKey.getColumnValues()[0] );
		}
		else {
			return ArrayHelper.concat( columnValues, embeddedKey.getColumnValues() );
		}
	}

	private boolean isCollectionOfPrimitives(String collectionRole, String[] embeddedColumnNames) {
		return embeddedColumnNames.length == 1 && collectionRole.equals( embeddedColumnNames[0] );
	}

	/*
	 * Example 1:
	 * MATCH (owner:ENTITY:MultiAddressAccount {login: {0}})
	 * CREATE (owner) -[r:addresses]-> (target:EMBEDDED:`MultiAddressAccount_addresses` {city: {1}, country: {2}, street1: {3}, `postal_code`: {6}})
	 * RETURN r
	 *
	 * Example 2:
	 * MATCH (owner:ENTITY:StoryGame {id: {0}}) - [:goodBranch] -> (e:EMBEDDED)
	 * CREATE (e) -[r:additionalEndings]-> (target:EMBEDDED:`StoryGame_goodBranch.additionalEndings` {score: {1}, text: {2}})
	 */
	private void createRelationshipforCollectionOfComponents(AssociationKey associationKey, String collectionRole, String[] embeddedColumnNames, Object[] embeddedColumnValues, StringBuilder queryBuilder) {
		int offset = associationKey.getEntityKey().getColumnNames().length;
		EmbeddedNodesTree tree = createEmbeddedTree( collectionRole, embeddedColumnNames, embeddedColumnValues, offset );
		if ( isPartOfEmbedded( collectionRole ) ) {
			String[] pathToEmbedded = appendEmbeddedNodes( collectionRole, queryBuilder );
			queryBuilder.append( " CREATE (e) -[r:" );
			appendRelationshipType( queryBuilder, pathToEmbedded[ pathToEmbedded.length - 1] );
		}
		else {
			queryBuilder.append( " CREATE (owner) -[r:" );
			appendRelationshipType( queryBuilder, collectionRole );
		}
		queryBuilder.append( "]-> " );
		queryBuilder.append( "(target:" );
		queryBuilder.append( EMBEDDED );
		queryBuilder.append( ":" );
		escapeIdentifier( queryBuilder, associationKey.getMetadata().getAssociatedEntityKeyMetadata().getEntityKeyMetadata().getTable() );
		int index = 0;
		int embeddedNumber = 0;

		// Append primitive properties
		if ( !tree.getProperties().isEmpty() ) {
			queryBuilder.append( " {" );
			for ( EmbeddedNodeProperty property : tree.getProperties() ) {
				escapeIdentifier( queryBuilder, property.getColumn() );
				queryBuilder.append( ": {" );
				queryBuilder.append( property.getParam() );
				queryBuilder.append( "}" );
				if ( index++ < tree.getProperties().size() - 1 ) {
					queryBuilder.append( ", " );
				}
			}
			queryBuilder.append( "}" );
		}
		queryBuilder.append( ")" );

		// Append relationships representing embedded properties
		Map<String, EmbeddedNodesTree> children = tree.getChildren();
		boolean first = true;
		for ( Entry<String, EmbeddedNodesTree> entry : children.entrySet() ) {
			index = 0;
			String relationshipType = entry.getKey();
			EmbeddedNodesTree child = entry.getValue();
			if ( first ) {
				first = false;
			}
			else {
				queryBuilder.append( ", (target)" );
			}
			queryBuilder.append( " - [:" );
			appendRelationshipType( queryBuilder, relationshipType );
			queryBuilder.append( "] -> " );
			queryBuilder.append( "(a" );
			queryBuilder.append( embeddedNumber++ );
			queryBuilder.append( ":" );
			queryBuilder.append( EMBEDDED );
			if ( !child.getProperties().isEmpty() ) {
				queryBuilder.append( " {" );
				for ( EmbeddedNodeProperty property : child.getProperties() ) {
					escapeIdentifier( queryBuilder, property.getColumn() );
					queryBuilder.append( ": {" );
					queryBuilder.append( property.getParam() );
					queryBuilder.append( "}" );
					if ( index++ < child.getProperties().size() - 1 ) {
						queryBuilder.append( ", " );
					}
				}
				queryBuilder.append( "}" );
			}
			queryBuilder.append( ")" );
		}
		queryBuilder.append( " RETURN r" );
	}

	/*
	 *  Append query part related to the creation of a relationship for a collection of primitive or a Map like in the following examples:
	 *
	 * 1) @ElemntCollection List<String> alternatives
	 * 2) @MapKeyColumn(name = "addressType") Map<String, Address> addresses
	 * Query example for embedded collection of primitives or Map:
	 *
	 * MATCH (owner:ENTITY:table {id: {0}})
	 * MERGE (owner) -[:relType]-> (e0:EMBEDDED) -[:relType2]-> (e:EMBEDDED)
	 * CREATE (e) -[r:relType2]-> (new:EMBEDDED { property: {1}})
	 * RETURN r
	 *
	 * MATCH (owner:ENTITY:table {id: {0}})
	 * CREATE (owner) -[r:relType2]-> (target:EMBEDDED { property: {1}})
	 * RETURN r
	 */
	private void createRelationshipForCollectionOfPrimitivesOrMap(AssociationKey associationKey, String collectionRole, String[] columnNames, StringBuilder queryBuilder) {
		String relationshipType = collectionRole;
		if ( isPartOfEmbedded( collectionRole ) ) {
			queryBuilder.append( " MERGE (owner) " );
			String[] pathToEmbedded = appendEmbeddedNodes( collectionRole, queryBuilder );
			relationshipType = pathToEmbedded[pathToEmbedded.length - 1];
			queryBuilder.append( " CREATE (e) -[r:" );
		}
		else {
			queryBuilder.append( " CREATE (owner) -[r:" );
		}
		escapeIdentifier( queryBuilder, relationshipType );
		queryBuilder.append( "]->(new:" );
		queryBuilder.append( EMBEDDED );
		queryBuilder.append( ":" );
		escapeIdentifier( queryBuilder, associationKey.getTable() );
		queryBuilder.append( " {" );
		// THe name of the property is the same as the relationship type
		escapeIdentifier( queryBuilder, relationshipType );
		queryBuilder.append( ": {" );
		queryBuilder.append( columnNames.length );
		queryBuilder.append( "}" );
		queryBuilder.append( "}" );
		queryBuilder.append( ")" );
		queryBuilder.append( " RETURN r" );
	}

	private Relationship executeQuery(GraphDatabaseService executionEngine, String query, Object[] queryValues) {
		Map<String, Object> params = params( queryValues );
		Result result = executionEngine.execute( query, params );
		return singleResult( result );
	}

	/*
	 * If the association is connected to embedded elements we also need to create the embedded relationships for this elements.
	 * This method will create an tree containing the information about the path to the embedded in a more managabel way.
	 */
	private EmbeddedNodesTree createEmbeddedTree(String collectionRole, String[] embeddedColumnNames, Object[] embeddedColumnValues, int offset) {
		EmbeddedNodesTree tree = new EmbeddedNodesTree();
		for ( int i = 0; i < embeddedColumnNames.length; i++ ) {
			String embeddedColumnName;
			if ( embeddedColumnNames[i].startsWith( collectionRole ) ) {
				embeddedColumnName = embeddedColumnNames[i].substring( collectionRole.length() + 1 );
			}
			else {
				embeddedColumnName = embeddedColumnNames[i];
			}

			if ( embeddedColumnValues[i] != null ) {
				if ( embeddedColumnName.contains( "." ) ) {
					int firstDot = embeddedColumnName.indexOf( "." );
					String relationshipType = embeddedColumnName.substring( 0, firstDot );
					String currentProperty = embeddedColumnName.substring( firstDot + 1 );
					appendSubTree( tree, currentProperty, relationshipType, offset + i );
				}
				else {
					EmbeddedNodeProperty property = new EmbeddedNodeProperty();
					property.setParam( offset + i );
					property.setColumn( embeddedColumnName );
					tree.addProperty( property );
				}
			}
		}
		return tree;
	}

	private void appendSubTree(EmbeddedNodesTree tree, String currentProperty, String relationshipType, int index) {
		EmbeddedNodesTree subTree = tree.getChild( relationshipType );
		if ( subTree == null ) {
			subTree = new EmbeddedNodesTree();
			tree.addChild( relationshipType, subTree );
		}
		if ( isPartOfEmbedded( currentProperty ) ) {
			int firstDot = currentProperty.indexOf( "." );
			String relType = currentProperty.substring( 0, firstDot );
			String subProperty = currentProperty.substring( firstDot + 1 );
			appendSubTree( tree, subProperty, relType, index );
		}
		else {
			EmbeddedNodeProperty property = new EmbeddedNodeProperty();
			property.setColumn( currentProperty );
			property.setParam( index );
			subTree.addProperty( property );
		}
	}

	/*
	 * Given an embedded properties path returns the cypher representation that can be appended to a MERGE or CREATE
	 * query.
	 */
	private static String[] appendEmbeddedNodes(String path, StringBuilder queryBuilder) {
		String[] columns = split( path );
		for ( int i = 0; i < columns.length - 1; i++ ) {
			queryBuilder.append( " - [:" );
			appendRelationshipType( queryBuilder, columns[i] );
			queryBuilder.append( "] ->" );
			if ( i < columns.length - 2 ) {
				queryBuilder.append( " (e" );
				queryBuilder.append( i );
				queryBuilder.append( ":" );
				queryBuilder.append( EMBEDDED );
				queryBuilder.append( ") MERGE (e" );
				queryBuilder.append( i );
				queryBuilder.append( ")" );
			}
		}
		queryBuilder.append( " (e:" );
		queryBuilder.append( EMBEDDED );
		queryBuilder.append( ")" );
		return columns;
	}

	private static class EmbeddedNodesTree {

		final List<EmbeddedNodeProperty> properties = new ArrayList<EmbeddedNodeProperty>();
		final Map<String, EmbeddedNodesTree> children = new HashMap<String, EmbeddedNodesTree>();

		public void addProperty(EmbeddedNodeProperty property) {
			properties.add( property );
		}

		public List<EmbeddedNodeProperty> getProperties() {
			return properties;
		}

		public void addChild(String relationshipType, EmbeddedNodesTree subTree) {
			children.put( relationshipType, subTree );
		}

		public EmbeddedNodesTree getChild(String relationshipType) {
			return children.get( relationshipType );
		}

		public Map<String, EmbeddedNodesTree> getChildren() {
			return children;
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append( "EmbeddedTree [properties=" );
			builder.append( properties );
			builder.append( ", children=" );
			builder.append( children );
			builder.append( "]" );
			return builder.toString();
		}
	}

	private class EmbeddedNodeProperty {

		private String column;
		private int param;

		public String getColumn() {
			return column;
		}

		public void setColumn(String column) {
			this.column = column;
		}

		public int getParam() {
			return param;
		}

		public void setParam(int param) {
			this.param = param;
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append( "[" );
			builder.append( column );
			builder.append( ", " );
			builder.append( param );
			builder.append( "]" );
			return builder.toString();
		}
	}
}
