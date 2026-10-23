import { Link } from '@tanstack/react-router'
import { SUPPLIER_CATEGORIES } from '../../types/supplier.types'
import { categoryLabels } from '../../utils/supplierFormat'
import { categoryImages } from './categoryImages'
import styles from './CategoryTiles.module.scss'

export const CategoryTiles = () => (
  <ul className={styles.categories} aria-label="Location categories">
    {SUPPLIER_CATEGORIES.map((category) => (
      <li key={category}>
        <Link to="/locations" search={{ category }} className={styles.category}>
          <span className={styles.tile} aria-hidden="true">
            <img src={categoryImages[category]} alt="" />
          </span>
          <span>{categoryLabels[category]}</span>
        </Link>
      </li>
    ))}
  </ul>
)
